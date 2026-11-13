using Edendale.Windows.Core;
using Edendale.Windows.Core.Handoff;
using Edendale.Windows.Core.Sync;
using Edendale.Windows.Services;
using Edendale.Windows.Services.Handoff;
using Edendale.Windows.Services.Sync;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;

namespace Edendale.Windows.Controls;

/// <summary>
/// Set Up Another Device and Receive from Another Device (FEATURES-27.1.md
/// §6, SYNC-PROTOCOL.md §9, the roles §6.2 gives a PC), and an
/// edendale://handoff link opened from outside the app. The person picks
/// which way the logins go first; which device shows the code is a separate
/// choice. As host this PC shows a QR code and a 6-digit code, each good for
/// 2 minutes and one attempt, and advertises itself only while the screen is
/// open. As guest it takes the code shown on the other device (J-PAKE), a
/// pasted QR link, or a link the camera opened. The sender confirms what
/// goes, with the receiver's name, before anything is sent.
/// </summary>
public static class HandoffDialog
{
    /// <summary>A link can carry any name; the question shows at most this much of it.</summary>
    private const int MaxLinkNameLength = 64;

    /// <summary>Set Up Another Device (<paramref name="sending"/>) or Receive from Another Device, from Settings → Sync.</summary>
    public static Task ShowAsync(XamlRoot root, bool sending) => RunAsync(root, new HandoffFlow(root, sending));

    /// <summary>
    /// A pairing code opened as a link (Windows Camera's QR scanner, or a link
    /// sent from a phone): asks "Pair with &lt;device&gt;?" with Send and
    /// Receive, as RootView.swift does on apple-27.1, then pairs with that code
    /// at once, because it is good for one attempt and two minutes. Nothing
    /// connects until the person picks a direction, and a sender still
    /// confirms what goes, with the receiver's name, before anything is sent.
    /// </summary>
    public static async Task ShowForLinkAsync(XamlRoot root, HandoffQrCode code)
    {
        var name = code.Name.Trim();
        if (name.Length > MaxLinkNameLength) name = name[..MaxLinkNameLength] + "…";
        var question = new ContentDialog
        {
            // A code without a name still asks the question.
            Title = Loc.Format("Pairing_LinkTitle", name.Length > 0 ? name : Loc.Get("Sync_AnotherDevice")),
            Content = new TextBlock { Text = Loc.Get("Pairing_LinkMessage"), TextWrapping = TextWrapping.Wrap, MaxWidth = 460 },
            PrimaryButtonText = Loc.Get("Pairing_LinkSend"),
            SecondaryButtonText = Loc.Get("Pairing_LinkReceive"),
            CloseButtonText = Loc.Get("Common_Cancel"),
            // Enter never picks which way the logins go; Esc cancels.
            DefaultButton = ContentDialogButton.None,
            XamlRoot = root,
        };
        var choice = await question.ShowAsync();
        if (choice == ContentDialogResult.None) return;
        await RunAsync(root, new HandoffFlow(root, sending: choice == ContentDialogResult.Primary, scanned: code));
    }

    /// <summary>
    /// Runs the flow, then what the receiver finishes here: a folder to pick
    /// or a Google sign-in opens the Location dialog, which asks about the old
    /// location itself; a location adopted outright offers to delete this
    /// PC's file at the one it replaced (§4), as Settings → Sync on Apple does.
    /// </summary>
    private static async Task RunAsync(XamlRoot root, HandoffFlow flow)
    {
        var previous = AppServices.SyncState.Location;
        var followUp = await flow.RunAsync();
        if (followUp is not null)
        {
            await SyncLocationDialog.ShowAsync(root, SpaceOf(followUp), followUp);
        }
        else if (flow.Adoption?.Replaced(previous) is { } old)
        {
            await SyncLocationDialog.OfferToDeleteOldFileAsync(root, old);
        }
    }

    private static string SpaceOf(HandoffFollowUp followUp) => followUp switch
    {
        HandoffFollowUp.PickFolder folder => folder.Space,
        HandoffFollowUp.GoogleSignIn google => google.Space,
        _ => "",
    };

    /// <summary>
    /// The Google follow-up's sentence, here and in the Location dialog: with
    /// the account the sender named, or without one when the sender had no
    /// email to give (SyncHandoffView.swift `followUpView`).
    /// </summary>
    internal static string GoogleSignInText(HandoffFollowUp.GoogleSignIn google) => string.IsNullOrEmpty(google.Hint)
        ? Loc.Format("Sync_GoogleRelinkNoHint", google.Space)
        : Loc.Format("Sync_GoogleRelink", google.Hint, google.Space);
}

/// <summary>One run of the dialog; its content changes per step, because only one ContentDialog may be open.</summary>
internal sealed class HandoffFlow
{
    private enum Step
    {
        Choose,
        Host,
        Guest,
        Confirm,
        Working,
        Done,
    }

    private const string Bullet = "• ";

    private readonly XamlRoot _root;
    private readonly DispatcherQueue _dispatcher;
    private readonly ContentDialog _dialog;
    private readonly StackPanel _content = new() { Spacing = 12, MinWidth = 420, MaxWidth = 520 };
    private readonly CancellationTokenSource _work = new();
    private readonly RadioButtons _direction = new();
    private readonly RadioButtons _mode = new();
    private readonly RadioButtons _peers = new();
    private readonly TextBox _codeOrLink = new() { IsSpellCheckEnabled = false, IsTextPredictionEnabled = false };
    private readonly TextBox _address = new() { IsSpellCheckEnabled = false, IsTextPredictionEnabled = false };
    private readonly TextBlock _status;
    private readonly List<(CheckBox Box, HandoffItem Item)> _ticks = [];
    /// <summary>A code opened as a link: no choice step and no guest form.</summary>
    private readonly HandoffQrCode? _scanned;
    private Step _step = Step.Choose;
    private bool _sending;
    private HandoffHostListener? _listener;
    private HandoffHostPairing? _pairing;
    private HandoffBrowser? _browser;
    private IReadOnlyList<HandoffPeer> _shownPeers = [];
    private HandoffRoleMessage? _peer;
    private TaskCompletionSource<HandoffOffer>? _decision;
    private DispatcherQueueTimer? _renew;
    // Set on the connection's thread, read on the UI thread once the dialog closes.
    private volatile HandoffAdoption? _adoption;

    public HandoffFlow(XamlRoot root, bool sending, HandoffQrCode? scanned = null)
    {
        _root = root;
        _sending = sending;
        _scanned = scanned;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        _status = Small("");
        AutomationProperties.SetLiveSetting(_status, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Polite);
        _direction.Items.Add(Loc.Get("Pairing_RoleSend"));
        _direction.Items.Add(Loc.Get("Pairing_RoleReceive"));
        _direction.SelectedIndex = sending ? 0 : 1;
        _direction.Header = Loc.Get("Pairing_RoleQuestion");
        _mode.Items.Add(Loc.Get("Pairing_ModeShow"));
        _mode.Items.Add(Loc.Get("Pairing_ModeEnter"));
        _mode.SelectedIndex = 0;
        _mode.Header = Loc.Get("Pairing_ModeQuestion");
        _dialog = new ContentDialog
        {
            Title = Title(sending),
            Content = new ScrollViewer { Content = _content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto },
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = root,
        };
        _dialog.PrimaryButtonClick += (sender, args) =>
        {
            args.Cancel = true;
            _ = OnPrimaryAsync();
        };
        _dialog.Closing += (_, _) =>
        {
            if (_step == Step.Confirm)
            {
                // Closing at the confirmation declines (§9.6 step 2); give the frame a moment to go.
                _decision?.TrySetResult(HandoffOffer.Declined);
                _ = Task.Delay(TimeSpan.FromSeconds(2)).ContinueWith(_ => _work.Cancel(), TaskScheduler.Default);
            }
            else
            {
                _work.Cancel();
            }
        };
    }

    private string DeviceName => AppServices.SyncState.DeviceName;

    /// <summary>What the receiver kept: the location it adopted, if any, and what is left to do here.</summary>
    public HandoffAdoption? Adoption => _adoption;

    private static string Title(bool sending) => Loc.Get(sending ? "Sync_SetUpTitle" : "Pairing_Title");

    /// <summary>Shows the dialog; returns what the receiver must still do here (pick a folder, sign in to Google).</summary>
    public async Task<HandoffFollowUp?> RunAsync()
    {
        if (_scanned is null)
        {
            ShowChoose();
        }
        else if (_sending && NothingToSend())
        {
            // Said before connecting, so the single-use code isn't spent on nothing.
            ShowOutcome(Loc.Get("Pairing_NothingToSend"));
        }
        else
        {
            ShowWorking(Loc.Get("Pairing_Connecting"));
            _dialog.Opened += PairWithScannedCode;
        }
        try
        {
            await _dialog.ShowAsync();
        }
        finally
        {
            _renew?.Stop();
            _listener?.Dispose();
            _browser?.Dispose();
            _pairing?.Dispose();
        }
        return _adoption?.FollowUp;
    }

    private static bool NothingToSend() => HandoffOffers.Choices(HandoffService.Stores, SyncFormat.WindowsPlatform).Count == 0;

    // ------------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------------

    private void ShowChoose()
    {
        _step = Step.Choose;
        _content.Children.Clear();
        _content.Children.Add(_direction);
        _content.Children.Add(_mode);
        _content.Children.Add(_status);
        _status.Text = "";
        SetButtons("Pairing_Continue", "Common_Cancel");
    }

    /// <summary>Just the status line and Cancel, while a connection is made.</summary>
    private void ShowWorking(string status)
    {
        _step = Step.Working;
        _content.Children.Clear();
        _status.Text = status;
        _content.Children.Add(_status);
        SetButtons(null, "Common_Cancel");
    }

    private async Task OnPrimaryAsync()
    {
        switch (_step)
        {
            case Step.Choose:
                _sending = _direction.SelectedIndex == 0;
                _dialog.Title = Title(_sending);
                if (_sending && NothingToSend())
                {
                    _status.Text = Loc.Get("Pairing_NothingToSend");
                    return;
                }
                if (_mode.SelectedIndex == 0) await StartHostAsync();
                else ShowGuest();
                break;
            case Step.Guest:
                await ConnectAsGuestAsync();
                break;
            case Step.Confirm:
                Send();
                break;
        }
    }

    // ---- Host: QR code and typed code ----

    private async Task StartHostAsync()
    {
        _step = Step.Host;
        SetButtons(null, "Common_Cancel");
        _content.Children.Clear();
        _status.Text = Loc.Get("Pairing_Waiting");
        _content.Children.Add(_status);
        try
        {
            _listener = new HandoffHostListener(ServeAsync);
            await _listener.StartAsync(DeviceName);
        }
        catch (Exception error)
        {
            SyncLog.Error("handoff", "listen", error);
            _status.Text = Loc.Get("Pairing_ConnectionFailed");
            return;
        }
        _renew = _dispatcher.CreateTimer();
        _renew.Interval = HandoffFrames.PairingLifetime;
        _renew.Tick += async (_, _) =>
        {
            if (_step == Step.Host) await ShowNewCodeAsync();
        };
        await ShowNewCodeAsync();
    }

    /// <summary>A fresh QR code and code: each is valid for 2 minutes and one attempt (§9.3, §9.4).</summary>
    private async Task ShowNewCodeAsync()
    {
        if (_listener is null) return;
        _renew?.Stop();
        if (HandoffService.LockedFor is { } locked && locked > TimeSpan.Zero)
        {
            _content.Children.Clear();
            _content.Children.Add(Text(Loc.Get("Pairing_LockedOut")));
            await Task.Delay(locked);
            if (_step != Step.Host) return;
        }
        _pairing?.Dispose();
        var pairing = new HandoffHostPairing(DeviceName);
        _pairing = pairing;
        var address = HandoffConnections.LocalAddress();

        _content.Children.Clear();
        // The other device's entry is the opposite of this PC's role (SyncHandoffView.swift `otherDeviceAction`).
        _content.Children.Add(Text(Loc.Format("Pairing_ScanInstructions", Loc.Get(_sending ? "Pairing_Title" : "Sync_SetUpTitle"))));
        var image = new Image { Width = 240, Height = 240, HorizontalAlignment = HorizontalAlignment.Left };
        AutomationProperties.SetName(image, Loc.Get("Pairing_QrDescription"));
        _content.Children.Add(image);
        _content.Children.Add(Small(Loc.Get("Pairing_CodeLabel")));
        var code = pairing.Code ?? "";
        var codeText = new TextBlock
        {
            Text = code.Length == 6 ? $"{code[..3]} {code[3..]}" : code,
            FontSize = 44,
            FontFamily = (FontFamily)Application.Current.Resources["DisplayFontFamily"],
            CharacterSpacing = 120,
            IsTextSelectionEnabled = true,
        };
        AutomationProperties.SetName(codeText, string.Join(' ', code.ToCharArray()));
        _content.Children.Add(codeText);
        if (address is not null) _content.Children.Add(Small(Loc.Format("Pairing_HostAddress", $"{address}:{_listener.Port}")));
        _status.Text = Loc.Get("Pairing_Waiting");
        _content.Children.Add(_status);
        _renew?.Start();

        try
        {
            // The QR link carries the secret: it is drawn, never logged.
            image.Source = await QrCodeImageFactory.CreateAsync(pairing.QrCode(address, _listener.Port).ToUri());
        }
        catch (Exception error)
        {
            SyncLog.Error("handoff", "qr", error);
        }
    }

    /// <summary>One connection to the host; runs on the listener's thread.</summary>
    private async Task ServeAsync(Stream stream)
    {
        var pairing = _pairing;
        if (pairing is null || _work.IsCancellationRequested) return;
        Enqueue(() =>
        {
            _renew?.Stop();
            _status.Text = Loc.Get("Pairing_Connecting");
        });
        try
        {
            var exchange = await HandoffSession.RunHostAsync(stream, pairing, Party(), _work.Token);
            HandoffService.RecordSuccess();
            Enqueue(() => ShowDone(exchange));
        }
        catch (HandoffException error) when (error.Reason is HandoffError.Code or HandoffError.Secret or HandoffError.Expired)
        {
            HandoffService.RecordFailure();
            SyncLog.Info("handoff", $"host pairing failed: {error.Reason}");
            Enqueue(async () =>
            {
                await ShowNewCodeAsync();
                _status.Text = Message(error);
            });
        }
        catch (Exception error) when (!_work.IsCancellationRequested)
        {
            SyncLog.Error("handoff", "host", error);
            Enqueue(() => ShowFailed(error));
        }
    }

    // ---- Guest: typed code, pasted link, or a link opened from outside ----

    private void ShowGuest()
    {
        _step = Step.Guest;
        _content.Children.Clear();
        _content.Children.Add(Text(Loc.Get("Pairing_GuestHint")));
        _peers.Header = Loc.Get("Pairing_DevicesFound");
        _content.Children.Add(_peers);
        _status.Text = Loc.Get("Pairing_Searching");
        _content.Children.Add(_status);
        _codeOrLink.Header = Loc.Get("Pairing_CodeOrLink");
        _codeOrLink.PlaceholderText = "123 456";
        _content.Children.Add(_codeOrLink);
        _address.Header = Loc.Get("Pairing_AddressLabel");
        _address.PlaceholderText = "192.168.1.20:52000";
        _content.Children.Add(_address);
        SetButtons("LinkSource_Connect", "Common_Cancel");

        StartBrowser();
        _codeOrLink.Focus(FocusState.Programmatic);
    }

    /// <summary>Browses for hosts while the guest's screen is open; the guest form lists them.</summary>
    private HandoffBrowser StartBrowser()
    {
        if (_browser is { } running) return running;
        var browser = new HandoffBrowser();
        _browser = browser;
        browser.Changed += (_, _) => Enqueue(RenderPeers);
        browser.Start();
        return browser;
    }

    private void RenderPeers()
    {
        if (_browser is null || _step != Step.Guest) return;
        var selectedId = SelectedPeer()?.Id;
        _shownPeers = _browser.Peers;
        _peers.Items.Clear();
        foreach (var peer in _shownPeers) _peers.Items.Add(peer.Name);
        var index = _shownPeers.ToList().FindIndex(peer => peer.Id == selectedId);
        _peers.SelectedIndex = index >= 0 ? index : _shownPeers.Count == 1 ? 0 : -1;
        if (_shownPeers.Count > 0 && _status.Text == Loc.Get("Pairing_Searching")) _status.Text = "";
    }

    /// <summary>The device picked in the guest form's list; none for a link opened from outside.</summary>
    private HandoffPeer? SelectedPeer() =>
        _scanned is null && _peers.SelectedIndex >= 0 && _peers.SelectedIndex < _shownPeers.Count ? _shownPeers[_peers.SelectedIndex] : null;

    private async Task ConnectAsGuestAsync()
    {
        var input = _codeOrLink.Text.Trim();
        if (HandoffQrCode.Parse(input) is { } qr)
        {
            await RunGuestAsync(new HandoffGuestPairing.Qr(qr), cancellation => ConnectForCodeAsync(qr, cancellation));
            return;
        }
        var digits = new string(input.Where(char.IsAsciiDigit).ToArray());
        if (!HandoffCrypto.IsCode(digits))
        {
            _status.Text = Loc.Get("Pairing_CodeInvalid");
            _codeOrLink.Focus(FocusState.Programmatic);
            return;
        }
        var target = HandoffConnections.ParseAddress(_address.Text);
        if (target is null && SelectedPeer() is { } peer) target = (peer.Address, peer.Port);
        if (target is not { } destination)
        {
            _status.Text = Loc.Get("Pairing_PickDevice");
            return;
        }
        await RunGuestAsync(new HandoffGuestPairing.Typed(digits), cancellation => HandoffConnections.ConnectAsync(destination.Host, destination.Port, cancellation));
    }

    /// <summary>A link opened from outside: pairs with its code as soon as the dialog shows.</summary>
    private void PairWithScannedCode(ContentDialog sender, ContentDialogOpenedEventArgs args)
    {
        _dialog.Opened -= PairWithScannedCode;
        if (_scanned is { } code) _ = RunGuestAsync(new HandoffGuestPairing.Qr(code), cancellation => ConnectForCodeAsync(code, cancellation));
    }

    /// <summary>
    /// Connects to the host a QR code names (<see cref="HandoffTargets"/>):
    /// its address for 10 seconds; then, in the guest form, the address typed
    /// there; then the host browsing finds by the code's name, or the only
    /// one (or the one picked in the form). Nothing is sent before this
    /// returns, so trying the next way never spends the single-use code.
    /// </summary>
    private async Task<(Stream Stream, IDisposable Connection)> ConnectForCodeAsync(HandoffQrCode code, CancellationToken cancellation)
    {
        // Started now, so a host is already known if the code's address fails.
        var browser = StartBrowser();
        if (code.Host is { Length: > 0 } host && code.Port is int port)
        {
            try
            {
                return await HandoffConnections.ConnectAsync(host, port, HandoffTargets.DirectConnectTimeout, cancellation);
            }
            catch (HandoffException error) when (error.Reason == HandoffError.ConnectionFailed && !cancellation.IsCancellationRequested)
            {
                // Another subnet, or an adapter the guest can't reach: find the host instead.
                SyncLog.Info("handoff", "direct connection failed, browsing");
            }
        }
        if (_scanned is null && HandoffConnections.ParseAddress(_address.Text) is { } typed)
        {
            return await HandoffConnections.ConnectAsync(typed.Host, typed.Port, cancellation);
        }
        _status.Text = Loc.Get("Pairing_Searching");
        var peer = await WaitForPeerAsync(browser, code.Name, SelectedPeer(), cancellation)
            ?? throw new HandoffException(HandoffError.ConnectionFailed, "no host found");
        _status.Text = Loc.Get("Pairing_Connecting");
        return await HandoffConnections.ConnectAsync(peer.Address, peer.Port, HandoffTargets.BrowsedConnectTimeout, cancellation);
    }

    /// <summary>The host to try, as soon as browsing finds one, for up to 15 seconds; null when none turned up.</summary>
    private static async Task<HandoffPeer?> WaitForPeerAsync(HandoffBrowser browser, string codeName, HandoffPeer? picked, CancellationToken cancellation)
    {
        HandoffPeer? Pick() => HandoffTargets.Fallback(browser.Peers, peer => peer.Name, codeName) ?? picked;
        var found = new TaskCompletionSource<HandoffPeer?>(TaskCreationOptions.RunContinuationsAsynchronously);
        void OnChanged(object? sender, EventArgs args)
        {
            if (Pick() is { } peer) found.TrySetResult(peer);
        }
        browser.Changed += OnChanged;
        try
        {
            using var limit = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            limit.CancelAfter(HandoffTargets.BrowseTimeout);
            using var expired = limit.Token.Register(() => found.TrySetResult(null));
            // Checked after subscribing, so a host found in between isn't missed.
            if (Pick() is { } known) return known;
            var peer = await found.Task;
            cancellation.ThrowIfCancellationRequested();
            return peer;
        }
        finally
        {
            browser.Changed -= OnChanged;
        }
    }

    /// <summary>Connects, then pairs and runs the exchange as the guest.</summary>
    private async Task RunGuestAsync(HandoffGuestPairing pairing, Func<CancellationToken, Task<(Stream Stream, IDisposable Connection)>> connect)
    {
        _step = Step.Working;
        SetButtons(null, "Common_Cancel");
        _status.Text = Loc.Get("Pairing_Connecting");
        try
        {
            var (stream, connection) = await connect(_work.Token);
            using (connection)
            using (stream)
            {
                var exchange = await HandoffSession.RunGuestAsync(stream, pairing, Party(), _work.Token);
                ShowDone(exchange);
            }
        }
        catch (Exception error) when (!_work.IsCancellationRequested)
        {
            SyncLog.Error("handoff", "guest", error);
            if (_scanned is not null)
            {
                // A link has no form to go back to, and its code may be spent.
                ShowFailed(error);
                return;
            }
            // Back to the form, so a mistyped code can be fixed.
            _step = Step.Guest;
            SetButtons("LinkSource_Connect", "Common_Cancel");
            _status.Text = error is HandoffException { Reason: HandoffError.ConnectionFailed } && _shownPeers.Count > 1 && SelectedPeer() is null
                ? Loc.Get("Pairing_PickDevice")
                : Message(error);
        }
        catch (Exception) when (_work.IsCancellationRequested)
        {
            // Closed: whatever the abandoned connection threw.
        }
    }

    // ---- After pairing: the exchange ----

    private HandoffParty Party()
    {
        var sending = _sending;
        return new HandoffParty
        {
            DeviceName = DeviceName,
            Platform = SyncFormat.WindowsPlatform,
            Role = sending ? HandoffRole.Sender : HandoffRole.Receiver,
            ProvideOffer = sending ? AskToSendAsync : null,
            StoreOffer = sending ? null : StoreAsync,
            PeerKnown = peer =>
            {
                _peer = peer;
                Enqueue(() => _status.Text = Loc.Format("Pairing_Connected", peer.Name));
            },
        };
    }

    /// <summary>§9.6 step 2: the sender shows what it is about to send, with the receiver's name, and waits.</summary>
    private Task<HandoffOffer> AskToSendAsync(HandoffRoleMessage peer, CancellationToken cancellation)
    {
        var decision = new TaskCompletionSource<HandoffOffer>(TaskCreationOptions.RunContinuationsAsynchronously);
        _decision = decision;
        cancellation.Register(() => decision.TrySetResult(HandoffOffer.Declined));
        Enqueue(() => ShowConfirm(peer));
        return decision.Task;
    }

    private void ShowConfirm(HandoffRoleMessage peer)
    {
        _step = Step.Confirm;
        _dialog.Title = Loc.Format("Pairing_ConfirmSendTitle", peer.Name);
        _content.Children.Clear();
        _ticks.Clear();
        var stores = HandoffService.Stores;
        var items = HandoffOffers.Choices(stores, peer.Platform);
        var heading = HandoffItemKind.Location;
        foreach (var item in items)
        {
            if (item.Kind != heading && item.Kind != HandoffItemKind.Location)
            {
                heading = item.Kind;
                _content.Children.Add(Small(Loc.Get(item.Kind == HandoffItemKind.Account ? "Pairing_SendAccounts" : "Pairing_SendLogins")));
            }
            var label = item.Kind == HandoffItemKind.Location ? LocationLabel(stores.Sync.Location) : $"{item.Detail} · {item.Title}";
            var box = new CheckBox { Content = label, IsChecked = item.Transfers, IsEnabled = item.Transfers };
            _ticks.Add((box, item));
            _content.Children.Add(box);
            if (!item.Transfers) _content.Children.Add(Small(Loc.Format("Pairing_GoogleSignsInAgain", peer.Name)));
        }
        if (items.Count == 0) _content.Children.Add(Text(Loc.Get("Pairing_NothingToSend")));
        SetButtons(items.Count == 0 ? null : "Pairing_Send", "Common_Cancel");
    }

    private static string LocationLabel(SyncLocationSettings? settings) => settings is null
        ? ""
        : settings.Kind == SyncLocationKind.Folder
            ? Loc.Format("Pairing_SendLocationFolder", settings.Space)
            : Loc.Format("Pairing_SendLocation", settings.SourceKind?.DisplayName() ?? "", settings.Space);

    private void Send()
    {
        if (_decision is null || _peer is null) return;
        var ticked = _ticks.Where(tick => tick.Box.IsChecked == true).Select(tick => tick.Item.Id).ToHashSet(StringComparer.Ordinal);
        var offer = ticked.Count == 0 ? HandoffOffer.Declined : HandoffOffers.Build(HandoffService.Stores, ticked, _peer.Platform);
        _step = Step.Working;
        SetButtons(null, "Common_Cancel");
        _content.Children.Clear();
        _status.Text = Loc.Format("Pairing_Connected", _peer.Name);
        _content.Children.Add(_status);
        _decision.TrySetResult(offer);
    }

    /// <summary>
    /// §9.6 step 4: validate and store, then answer with the result; what
    /// needs the person waits until after. Adopting a location lists it and
    /// starts its first sync without waiting for it, so the result goes back
    /// well inside the sender's 2 minutes (§9.2).
    /// </summary>
    private async Task<HandoffResult> StoreAsync(HandoffOffer offer, HandoffRoleMessage peer, CancellationToken cancellation)
    {
        var adoption = await HandoffOffers.AdoptAsync(offer, HandoffService.Stores, HandoffService.Validators, cancellation);
        _adoption = adoption;
        return adoption.Result;
    }

    private void ShowDone(HandoffExchange exchange)
    {
        _step = Step.Done;
        _renew?.Stop();
        _content.Children.Clear();
        var name = exchange.Peer.Name;
        if (!exchange.Offer.Approved)
        {
            _content.Children.Add(Text(Loc.Get("Pairing_Declined")));
        }
        else
        {
            var result = exchange.Result;
            _content.Children.Add(Text(Loc.Format(_sending ? "Pairing_Sent" : "Pairing_Received", name)));
            _content.Children.Add(Small(Loc.Plural("Pairing_StoredOne", "Pairing_StoredOther", result.Stored.Count)));
            foreach (var item in result.Stored) _content.Children.Add(Small(Bullet + DescribeItem(item)));
            if (result.Failed.Count > 0)
            {
                _content.Children.Add(Small(Loc.Plural("Pairing_FailedOne", "Pairing_FailedOther", result.Failed.Count)));
                foreach (var failure in result.Failed) _content.Children.Add(Small(Bullet + DescribeFailure(failure)));
            }
            switch (_adoption?.FollowUp)
            {
                case HandoffFollowUp.PickFolder folder:
                    _content.Children.Add(Text(Loc.Format("Sync_FolderRelink", folder.Space)));
                    break;
                case HandoffFollowUp.GoogleSignIn google:
                    _content.Children.Add(Text(HandoffDialog.GoogleSignInText(google)));
                    break;
            }
        }
        SetButtons(null, "Common_OK");
    }

    /// <summary>
    /// A result item in words (SyncHandoffView.swift `describe`): "Sync
    /// location", "OneDrive account", "SFTP login for nas.local", or the item
    /// as sent when this build doesn't know its kind.
    /// </summary>
    private static string DescribeItem(string item)
    {
        var parts = HandoffItemNames.Parse(item);
        return parts.Kind switch
        {
            HandoffItemName.Location => Loc.Get("Pairing_ItemLocation"),
            HandoffItemName.Account => Loc.Format("Pairing_ItemAccount", KindName(parts.SourceKind)),
            HandoffItemName.Login => Loc.Format("Pairing_ItemLogin", KindName(parts.SourceKind), parts.Host),
            _ => item,
        };
    }

    private static string KindName(string? raw) => MediaSourceKinds.FromRawValue(raw)?.DisplayName() ?? raw ?? "";

    /// <summary>A failed item, with the receiver's reason when it gave one (Android may send none).</summary>
    private static string DescribeFailure(HandoffFailure failure) => string.IsNullOrEmpty(failure.Reason)
        ? Loc.Format("Pairing_FailedItem", DescribeItem(failure.Item))
        : Loc.Format("Pairing_FailedItemReason", DescribeItem(failure.Item), failure.Reason);

    private void ShowFailed(Exception error) => ShowOutcome(Message(error));

    /// <summary>The last step: a sentence and OK.</summary>
    private void ShowOutcome(string message)
    {
        _step = Step.Done;
        _renew?.Stop();
        _content.Children.Clear();
        _content.Children.Add(Text(message));
        SetButtons(null, "Common_OK");
    }

    /// <summary>What a failure means to the person; never the exception's own text, which can name hosts.</summary>
    private static string Message(Exception error) => error switch
    {
        HandoffException { Reason: HandoffError.Secret or HandoffError.Expired } => Loc.Get("Pairing_WrongSecret"),
        HandoffException { Reason: HandoffError.Code } => Loc.Get("Pairing_WrongCode"),
        HandoffException { Reason: HandoffError.Role } => Loc.Get("Pairing_SameRole"),
        HandoffException { Reason: HandoffError.Busy } => Loc.Get("Pairing_Busy"),
        HandoffException { Reason: HandoffError.Version } => Loc.Get("Pairing_Version"),
        HandoffException { Reason: HandoffError.Timeout } => Loc.Get("Pairing_Timeout"),
        HandoffException { Reason: HandoffError.Declined } => Loc.Get("Pairing_Declined"),
        HandoffException { Reason: HandoffError.ConnectionFailed } => Loc.Get("Pairing_ConnectionFailed"),
        _ => Loc.Get("Pairing_Failed"),
    };

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private void SetButtons(string? primaryKey, string closeKey)
    {
        _dialog.PrimaryButtonText = primaryKey is null ? "" : Loc.Get(primaryKey);
        _dialog.CloseButtonText = Loc.Get(closeKey);
        if (primaryKey is not null) RefocusPrimaryButton();
        // Set last, and afresh, so the dialog works out its default button
        // again with focus where it now is.
        _dialog.DefaultButton = primaryKey is null ? ContentDialogButton.None : ContentDialogButton.Primary;
    }

    /// <summary>
    /// Hiding the primary button while it has focus leaves focus on Cancel,
    /// and ContentDialog accents its default button, and lets Enter press it,
    /// only while focus is outside its button row or on that button. So when
    /// the primary button returns, focus goes back to it, as when the dialog opens.
    /// </summary>
    private void RefocusPrimaryButton()
    {
        // CommandSpace and PrimaryButton are the template's names for that row and button.
        if (FocusManager.GetFocusedElement(_root) is Button focused
            && VisualTreeHelper.GetParent(focused) is Panel { Name: "CommandSpace" } row
            && row.Children.OfType<Button>().FirstOrDefault(button => button.Name == "PrimaryButton") is { } primary
            && primary != focused)
        {
            primary.Focus(FocusState.Programmatic);
        }
    }

    private void Enqueue(Action action) => _dispatcher.TryEnqueue(() => action());

    private void Enqueue(Func<Task> action) => _dispatcher.TryEnqueue(async () => await action());

    private static TextBlock Text(string text) => new() { Text = text, TextWrapping = TextWrapping.Wrap };

    private static TextBlock Small(string text) => new()
    {
        Text = text,
        Style = (Style)Application.Current.Resources["BodySMTextStyle"],
        TextWrapping = TextWrapping.Wrap,
    };
}
