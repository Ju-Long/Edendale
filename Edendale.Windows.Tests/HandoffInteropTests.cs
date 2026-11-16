using System.Diagnostics;
using System.Text;
using Edendale.Windows.Core.Handoff;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The handoff as the other platforms speak it on apple-27.1 (K.7): logins
/// in Android's flat shape and Apple's nested `credential`, a result whose
/// failures carry no reason, the names of the items a result reports, waits
/// that end on time even when the network never answers, and where a guest
/// connects when the code's address doesn't answer. Ports
/// HandoffInteropTests.swift where Apple has a case.
/// </summary>
[TestClass]
public sealed class HandoffInteropTests
{
    private static HandoffLogin LoginIn(string loginJson)
    {
        var offer = HandoffMessages.DecodeOffer(Encoding.UTF8.GetBytes($$"""{"type":"offer","status":"approved","logins":[{{loginJson}}]}"""));
        return offer.LoginList.Single();
    }

    // ------------------------------------------------------------------
    // Logins (§9.7 on main): flat on every platform, nested from Apple
    // ------------------------------------------------------------------

    [TestMethod]
    public void AndroidsFlatSftpLoginReads()
    {
        var login = LoginIn("""{"kind":"sftp","host":"nas.local","port":2222,"user":"me","password":"pw","hostKeyFingerprint":"SHA256:abc","certificateFingerprint":null,"addresses":["192.168.1.2"]}""");
        Assert.AreEqual("me", login.User);
        Assert.AreEqual("pw", login.Password);
        Assert.AreEqual(2222, login.Port);
        Assert.AreEqual("SHA256:abc", login.HostKeyFingerprint);
        CollectionAssert.AreEqual(new[] { "192.168.1.2" }, login.Addresses!.ToArray());
    }

    [TestMethod]
    public void AFlatS3LoginKeepsItsAddressingStyle()
    {
        var login = LoginIn("""{"kind":"s3","host":"s3.us-east-1.amazonaws.com","user":"AKIA","password":"secret","s3":{"endpoint":"https://s3.us-east-1.amazonaws.com","region":"us-east-1","bucket":"films","pathStyle":true}}""");
        Assert.IsTrue(login.S3!.PathStyle);
        Assert.AreEqual("films", login.S3.Bucket);
        Assert.IsNull(login.Port);
    }

    [TestMethod]
    public void ApplesNestedCredentialReads()
    {
        var sftp = LoginIn("""{"kind":"sftp","host":"nas.local","port":null,"credential":{"username":"me","password":"pw","port":2222},"hostKeyFingerprint":"SHA256:abc"}""");
        Assert.AreEqual("me", sftp.User);
        Assert.AreEqual("pw", sftp.Password);
        Assert.AreEqual(2222, sftp.Port, "the nested shape keeps an SFTP port in the credential");
        Assert.AreEqual("SHA256:abc", sftp.HostKeyFingerprint);

        var s3 = LoginIn("""{"kind":"s3","host":"minio.local","credential":{"username":"AKIA","password":"secret","s3":{"endpoint":"https://minio.local","region":"eu-west-1","bucket":"films","usesPathStyle":true}}}""");
        Assert.IsTrue(s3.S3!.PathStyle);
        Assert.AreEqual("eu-west-1", s3.S3.Region);
    }

    [TestMethod]
    public void WhenBothShapesComeTheFlatOneWins()
    {
        var login = LoginIn("""{"kind":"webdav","host":"dav.local","user":"flat","password":"a","credential":{"username":"nested","password":"b"}}""");
        Assert.AreEqual("flat", login.User);
        Assert.AreEqual("a", login.Password);
    }

    [TestMethod]
    public void ALoginWithNeitherShapeIsMalformed_NotAGuestLogin()
    {
        var error = Assert.ThrowsException<HandoffException>(() => LoginIn("""{"kind":"smb","host":"nas.local"}"""));
        Assert.AreEqual(HandoffError.Malformed, error.Reason);
        Assert.ThrowsException<HandoffException>(() => LoginIn("""{"kind":"smb","host":"nas.local","credential":{}}"""), "the nested shape needs its username");
    }

    [TestMethod]
    public void WindowsWritesTheFlatShapeOnly()
    {
        var offer = new HandoffOffer(true, Logins: [new HandoffLogin("sftp", "nas.local", 2222, "me", "pw", "SHA256:abc")]);
        var text = Encoding.UTF8.GetString(HandoffMessages.EncodeOffer(offer));
        Assert.IsFalse(text.Contains("credential"), text);
        Assert.IsTrue(text.Contains("\"user\""), text);
        Assert.AreEqual("me", HandoffMessages.DecodeOffer(HandoffMessages.EncodeOffer(offer)).LoginList.Single().User);
    }

    // ------------------------------------------------------------------
    // Results: Android may send no reason; items are named
    // ------------------------------------------------------------------

    [TestMethod]
    public void AResultWithANullReasonDecodes()
    {
        var result = HandoffMessages.DecodeResult(Encoding.UTF8.GetBytes(
            """{"type":"result","stored":["location"],"failed":[{"item":"login:smb:nas.local","reason":null},{"item":"account:gdrive:x","reason":"rejected"}]}"""));
        CollectionAssert.AreEqual(new[] { "location" }, result.Stored.ToArray());
        Assert.IsTrue(string.IsNullOrEmpty(result.Failed[0].Reason));
        Assert.AreEqual("rejected", result.Failed[1].Reason);
    }

    [TestMethod]
    public void ResultItemsSplitIntoTheirParts()
    {
        Assert.AreEqual(new HandoffItemParts(HandoffItemName.Location, null, null), HandoffItemNames.Parse("location"));
        Assert.AreEqual(new HandoffItemParts(HandoffItemName.Account, "gdrive", null), HandoffItemNames.Parse("account:gdrive:abc"));
        Assert.AreEqual(new HandoffItemParts(HandoffItemName.Login, "sftp", "fe80::1"), HandoffItemNames.Parse("login:sftp:fe80::1"), "a host keeps its colons");
        Assert.AreEqual(HandoffItemName.Other, HandoffItemNames.Parse("x").Kind);
        Assert.AreEqual(HandoffItemName.Other, HandoffItemNames.Parse("login:sftp").Kind);
        Assert.AreEqual(HandoffItemName.Other, HandoffItemNames.Parse(null).Kind);
    }

    // ------------------------------------------------------------------
    // Deadlines (deadlinesEndWaitsTheNetworkWouldNeverResume)
    // ------------------------------------------------------------------

    [TestMethod]
    public async Task ADeadlineEndsAWaitThatNeverResumes()
    {
        var hold = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var error = await Assert.ThrowsExceptionAsync<HandoffException>(() => HandoffDeadline.RunAsync(
            TimeSpan.FromMilliseconds(50), () => hold.TrySetResult(), _ => hold.Task.ContinueWith(_ => 0), CancellationToken.None));
        Assert.AreEqual(HandoffError.Timeout, error.Reason);
        Assert.IsTrue(hold.Task.IsCompleted, "expire ended the wait");
    }

    [TestMethod]
    public async Task ADeadlinePassesOnWorkThatFinishesOrFails()
    {
        Assert.AreEqual(42, await HandoffDeadline.RunAsync(TimeSpan.FromSeconds(5), () => { }, _ => Task.FromResult(42), CancellationToken.None));
        var error = await Assert.ThrowsExceptionAsync<HandoffException>(() => HandoffDeadline.RunAsync<int>(
            TimeSpan.FromSeconds(5), () => { }, _ => throw new HandoffException(HandoffError.Secret), CancellationToken.None));
        Assert.AreEqual(HandoffError.Secret, error.Reason);

        using var cancelled = new CancellationTokenSource();
        cancelled.Cancel();
        await Assert.ThrowsExceptionAsync<OperationCanceledException>(() => HandoffDeadline.RunAsync(
            TimeSpan.FromSeconds(5), () => { }, token => Task.Delay(Timeout.Infinite, token).ContinueWith(_ => 0, TaskContinuationOptions.OnlyOnRanToCompletion), cancelled.Token));
    }

    /// <summary>A stream whose reads ignore cancellation and end only when it is closed, like a WinRT socket's.</summary>
    private sealed class NeverEndingStream : Stream
    {
        private readonly TaskCompletionSource<int> _closed = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) => _closed.Task;
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) => new(_closed.Task);
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        protected override void Dispose(bool disposing)
        {
            _closed.TrySetException(new ObjectDisposedException(nameof(NeverEndingStream)));
            base.Dispose(disposing);
        }
    }

    [TestMethod]
    public async Task AFrameReadEndsOnTimeOnAStreamThatIgnoresCancellation()
    {
        var clock = Stopwatch.StartNew();
        var error = await Assert.ThrowsExceptionAsync<HandoffException>(() =>
            HandoffFrames.ReadAsync(new NeverEndingStream(), TimeSpan.FromMilliseconds(100), CancellationToken.None));
        Assert.AreEqual(HandoffError.Timeout, error.Reason);
        Assert.IsTrue(clock.Elapsed < TimeSpan.FromSeconds(2), clock.Elapsed.ToString());
    }

    // ------------------------------------------------------------------
    // Where a guest connects when the code's address doesn't answer
    // ------------------------------------------------------------------

    private sealed record Peer(string Name);

    [TestMethod]
    public void TheFallbackHostIsTheOneTheCodeNames_ElseTheOnlyOne()
    {
        var tv = new Peer("Living Room TV");
        var mac = new Peer("Studio Mac");
        Assert.AreSame(tv, HandoffTargets.Fallback([mac, tv], peer => peer.Name, "Living Room TV"), "a name match beats the rest");
        Assert.AreSame(mac, HandoffTargets.Fallback([mac], peer => peer.Name, "Living Room TV"), "the only host on the network");
        Assert.IsNull(HandoffTargets.Fallback([mac, new Peer("Phone")], peer => peer.Name, "Living Room TV"), "two strangers: the person picks");
        Assert.IsNull(HandoffTargets.Fallback(Array.Empty<Peer>(), peer => peer.Name, "Living Room TV"));
    }
}
