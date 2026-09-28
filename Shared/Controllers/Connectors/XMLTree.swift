//
//  XMLTree.swift
//  Edendale
//
//  A minimal element tree over Foundation's XMLParser, for the two XML
//  listings Edendale reads: WebDAV `multistatus` and S3 `ListBucketResult`.
//  Elements are named by local name (namespaces processed away), so `D:href`,
//  `d:href`, and `href` in the DAV: namespace all read as "href".
//

import Foundation

nonisolated final class XMLTree: @unchecked Sendable {
    let name: String
    private(set) var children: [XMLTree] = []
    private(set) var text = ""

    init(name: String) {
        self.name = name
    }

    /// Parses a document; `nil` when it isn't well-formed XML.
    static func parse(_ data: Data) -> XMLTree? {
        let builder = Builder()
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = true
        parser.shouldResolveExternalEntities = false
        parser.delegate = builder
        guard parser.parse() else { return nil }
        return builder.root
    }

    /// The first direct child with this local name.
    func child(_ name: String) -> XMLTree? {
        children.first { $0.name == name }
    }

    func children(_ name: String) -> [XMLTree] {
        children.filter { $0.name == name }
    }

    /// Every descendant with this local name, depth first.
    func descendants(_ name: String) -> [XMLTree] {
        children.flatMap { ($0.name == name ? [$0] : []) + $0.descendants(name) }
    }

    /// Trimmed text of the first direct child with this name.
    func value(_ name: String) -> String? {
        child(name).map { $0.text.trimmingCharacters(in: .whitespacesAndNewlines) }
    }

    /// Explicitly nonisolated: the module defaults to the main actor, and
    /// XMLParser calls its delegate on whatever thread parses.
    private nonisolated final class Builder: NSObject, XMLParserDelegate {
        var root: XMLTree?
        private var stack: [XMLTree] = []

        func parser(
            _ parser: XMLParser,
            didStartElement elementName: String,
            namespaceURI: String?,
            qualifiedName: String?,
            attributes: [String: String] = [:]
        ) {
            let node = XMLTree(name: elementName)
            if let parent = stack.last {
                parent.children.append(node)
            } else {
                root = node
            }
            stack.append(node)
        }

        func parser(
            _ parser: XMLParser,
            didEndElement elementName: String,
            namespaceURI: String?,
            qualifiedName: String?
        ) {
            _ = stack.popLast()
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            stack.last?.text += string
        }

        func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
            stack.last?.text += String(decoding: CDATABlock, as: UTF8.self)
        }
    }
}
