//
//  GoogleDriveMigrationTests.swift
//  EdendaleTests
//
//  The clean break when Google Drive moved from `drive.readonly` to
//  `drive.file` (ENHANCEMENT.md J.14, D1): at launch, Google accounts
//  without `drive.file` and every Google Drive folder source go, once, with
//  a notice; `drive.file` accounts, "Videos you chose" sources, and other
//  providers stay. Apple TV runs the same code against its own Keychain.
//  In-memory Keychain and library only; no network.
//

import Foundation
import SwiftData
import Testing
@testable import Edendale

@MainActor
struct GoogleDriveMigrationTests {

    private let oldScopes = ["openid", "email", "https://www.googleapis.com/auth/drive.readonly"]

    private func account(_ kind: MediaSourceKind, subject: String, scopes: [String]) -> CloudAccount {
        CloudAccount(
            kind: kind, subject: subject, email: "\(subject)@example.com", displayName: nil,
            refreshToken: "refresh-\(subject)", scopes: scopes, driveID: kind == .oneDrive ? "drive-1" : nil
        )
    }

    private func store(vault: CloudAccountVault, secrets: InMemorySecretStore) -> CloudAccountStore {
        let tokens = CloudTokenProvider(vault: vault, session: HTTPStub.session(), configuration: { _ in nil })
        return CloudAccountStore(vault: vault, secrets: secrets, tokens: tokens, session: HTTPStub.session())
    }

    private func folder(_ path: String, kind: MediaSourceKind, in context: ModelContext) -> VideoFolder {
        let folder = VideoFolder(name: path, folderPath: path, sourceKind: kind)
        folder.accountKey = URL(string: path)?.host()
        context.insert(folder)
        return folder
    }

    @Test func removesOldGoogleAccountsAndSourcesOnce() throws {
        let secrets = InMemorySecretStore()
        let vault = CloudAccountVault(store: secrets)
        let old = account(.googleDrive, subject: "sub-old", scopes: oldScopes)
        let current = account(.googleDrive, subject: "permission-new", scopes: CloudProviders.googleScopes)
        let dropbox = account(.dropbox, subject: "dbid", scopes: CloudProviders.dropboxScopes)
        for account in [old, current, dropbox] { try vault.save(account) }

        let schema = Schema([VideoFolder.self, Movie.self, TVShow.self, Episode.self])
        let container = try ModelContainer(
            for: schema,
            configurations: [ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)]
        )
        let context = container.mainContext
        let library = LibraryController(modelContext: context, enrichesMetadata: false)

        // The old account's folder source, with an item.
        let oldFolder = folder(
            SourceURL.accountItem(kind: .googleDrive, account: old.key, ids: ["folder-1"], name: "Films").absoluteString,
            kind: .googleDrive, in: context
        )
        oldFolder.movies.append(Movie(
            localTitle: "Heat",
            filePath: SourceURL.accountItem(kind: .googleDrive, account: old.key, ids: ["v1"], name: "Heat.1995.mkv").absoluteString
        ))
        // A folder source whose account another device's migration already
        // removed through the iCloud Keychain.
        _ = folder(
            SourceURL.accountItem(kind: .googleDrive, account: "0123456789abcdef0123456789abcdef", ids: ["root"], name: "My Drive").absoluteString,
            kind: .googleDrive, in: context
        )
        let chosen = folder(GoogleDriveConnector.chosenFolderURL(accountKey: current.key).absoluteString, kind: .googleDrive, in: context)
        let dropboxFolder = folder("dropbox://\(dropbox.key)/id%3Aabc/Films", kind: .dropbox, in: context)
        let local = folder("/Users/me/Movies", kind: .local, in: context)
        try context.save()

        let store = store(vault: vault, secrets: secrets)
        #expect(!store.needsGoogleDriveRelinkNotice)
        store.migrateGoogleDriveToFileAccess(library: library)

        #expect(store.needsGoogleDriveRelinkNotice)
        #expect(Set(vault.all().map(\.key)) == [current.key, dropbox.key])
        #expect(Set(store.accounts.map(\.key)) == [current.key, dropbox.key])
        let remaining = try context.fetch(FetchDescriptor<VideoFolder>())
        #expect(Set(remaining.map(\.id)) == [chosen.id, dropboxFolder.id, local.id])
        #expect(try context.fetch(FetchDescriptor<Movie>()).isEmpty)

        // The next launch finds nothing to remove and says nothing.
        let nextLaunch = self.store(vault: vault, secrets: secrets)
        nextLaunch.migrateGoogleDriveToFileAccess(library: library)
        #expect(!nextLaunch.needsGoogleDriveRelinkNotice)
        #expect(vault.all().count == 2)
        #expect(try context.fetch(FetchDescriptor<VideoFolder>()).count == 3)
    }

    @Test func anOldAccountSyncedInLaterIsRemovedToo() throws {
        let secrets = InMemorySecretStore()
        let vault = CloudAccountVault(store: secrets)
        let schema = Schema([VideoFolder.self, Movie.self, TVShow.self, Episode.self])
        let container = try ModelContainer(
            for: schema,
            configurations: [ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)]
        )
        let library = LibraryController(modelContext: container.mainContext, enrichesMetadata: false)

        let store = store(vault: vault, secrets: secrets)
        store.migrateGoogleDriveToFileAccess(library: library)
        #expect(!store.needsGoogleDriveRelinkNotice)

        // An older build on another device signs in with drive.readonly and
        // the account arrives through iCloud Keychain: under that grant the
        // Drive root would list every file, so it goes at the next launch.
        try vault.save(account(.googleDrive, subject: "sub-old", scopes: oldScopes))
        let nextLaunch = self.store(vault: vault, secrets: secrets)
        #expect(nextLaunch.accounts.count == 1)
        nextLaunch.migrateGoogleDriveToFileAccess(library: library)
        #expect(nextLaunch.needsGoogleDriveRelinkNotice)
        #expect(vault.all().isEmpty)
    }
}
