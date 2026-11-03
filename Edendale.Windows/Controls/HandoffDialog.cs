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
/// Set Up Another Device and Pair with Another Device (FEATURES-27.1.md §6,
/// SYNC-PROTOCOL.md §9, the roles §6.2 gives a PC). The person picks which way
/// the logins go first; which device shows the code is a separate choice. As
/// host this PC shows a QR code and a 6-digit code, each good for 2 minutes
/// and one attempt, and advertises itself only while the screen is open. As
/// guest it takes the code shown on the other device (J-PAKE) or a pasted
/// QR link. The sender confirms what goes, with the receiver's name, before
/// anything is sent.
/// </summary>
public static class HandoffDialog
{
    public static async Task ShowAsync(XamlRoot root, bool sending)
    {
        var flow = new HandoffFlow(root, sending);
        var followUp = await flow.RunAsync();
        if (followUp is not null) await SyncLocationDialog.ShowAsync(root, SpaceOf(followUp), followUp);
    }

    private static string SpaceOf(HandoffFollowUp followUp) => followUp switch
    {
        HandoffFollowUp.PickFolder folder => folder.Space,
        HandoffFollowUp.GoogleSignIn google => google.Space,
        _ => "",
    };
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
    private Step _step = Step.Choose;
    private bool _sending;
    private HandoffHostListener? _listener;
    private HandoffHostPairing? _pairing;
    private HandoffBrowser? _browser;
    private IReadOnlyList<HandoffPeer> _shownPeers = [];
    private HandoffRoleMessage? _peer;
    private TaskCompletionSource<HandoffOffer>? _decision;
    private DispatcherQueueTimer? _renew;
    private HandoffFollowUp? _followUp;

    public HandoffFlow(XamlRoot root, bool sending)
    {
        _root = root;
        _sending = sending;
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
            Title = Loc.Get(sending ? "Sync_SetUpTitle" : "Pairing_Title"),
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

    /// <summary>Shows the dialog; returns what the receiver must still do here (pick a folder, sign in to Google).</summary>
    public async Task<HandoffFollowUp?> RunAsync()
    {
        ShowChoose();
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
        return _followUp;
    }

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

    private async Task OnPrimaryAsync()
    {
        switch (_step)
        {
            case Step.Choose:
                _sending = _direction.SelectedIndex == 0;
                _dialog.Title = Loc.Get(_sending ? "Sync_SetUpTitle" : "Pairing_Title");
                if (_sending && HandoffOffers.Choices(HandoffService.Stores, SyncFormat.WindowsPlatform).Count == 0)
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
        _content.Children.Add(Text(Loc.Get("Pairing_Instructions")));
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

    // ---- Guest: typed code or pasted link ----

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

        _browser = new HandoffBrowser();
        _browser.Changed += (_, _) => Enqueue(RenderPeers);
        _browser.Start();
        _codeOrLink.Focus(FocusState.Programmatic);
    }

    private void RenderPeers()
    {
        if (_browser is null || _step != Step.Guest) return;
        var selectedId = _peers.SelectedIndex >= 0 && _peers.SelectedIndex < _shownPeers.Count ? _shownPeers[_peers.SelectedIndex].Id : null;
        _shownPeers = _browser.Peers;
        _peers.Items.Clear();
        foreach (var peer in _shownPeers) _peers.Items.Add(peer.Name);
        var index = _shownPeers.ToList().FindIndex(peer => peer.Id == selectedId);
        _peers.SelectedIndex = index >= 0 ? index : _shownPeers.Count == 1 ? 0 : -1;
        if (_shownPeers.Count > 0 && _status.Text == Loc.Get("Pairing_Searching")) _status.Text = "";
    }

    private async Task ConnectAsGuestAsync()
    {
        var input = _codeOrLink.Text.Trim();
        HandoffGuestPairing pairing;
        (string Host, int Port)? target = null;
        if (HandoffQrCode.Parse(input) is { } qr)
        {
            pairing = new HandoffGuestPairing.Qr(qr);
            if (qr.Host is { Length: > 0 } host && qr.Port is int port) target = (host, port);
            else if (_shownPeers.FirstOrDefault(peer => peer.Name == qr.Name) is { } named) target = (named.Address, named.Port);
        }
        else
        {
            var digits = new string(input.Where(char.IsAsciiDigit).ToArray());
            if (!HandoffCrypto.IsCode(digits))
            {
                _status.Text = Loc.Get("Pairing_CodeInvalid");
                _codeOrLink.Focus(FocusState.Programmatic);
                return;
            }
            pairing = new HandoffGuestPairing.Typed(digits);
        }
        target ??= HandoffConnections.ParseAddress(_address.Text);
        if (target is null && _peers.SelectedIndex >= 0 && _peers.SelectedIndex < _shownPeers.Count)
        {
            var peer = _shownPeers[_peers.SelectedIndex];
            target = (peer.Address, peer.Port);
        }
        if (target is not { } destination)
        {
            _status.Text = Loc.Get("Pairing_PickDevice");
            return;
        }

        _step = Step.Working;
        SetButtons(null, "Common_Cancel");
        _status.Text = Loc.Get("Pairing_Connecting");
        try
        {
            var (stream, connection) = await HandoffConnections.ConnectAsync(destination.Host, destination.Port, _work.Token);
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
            // Back to the form, so a mistyped code can be fixed.
            _step = Step.Guest;
            SetButtons("LinkSource_Connect", "Common_Cancel");
            _status.Text = Message(error);
        }
        catch (OperationCanceledException)
        {
            // Closed.
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

    /// <summary>§9.6 step 4: validate and store, then answer with the result; what needs the person waits until after.</summary>
    private async Task<HandoffResult> StoreAsync(HandoffOffer offer, HandoffRoleMessage peer, CancellationToken cancellation)
    {
        var adoption = await HandoffOffers.AdoptAsync(offer, HandoffService.Stores, HandoffService.Validators, cancellation);
        _followUp = adoption.FollowUp;
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
            _content.Children.Add(Text(Loc.Format(_sending ? "Pairing_Sent" : "Pairing_Received", name)));
            _content.Children.Add(Small(Loc.Plural("Pairing_StoredOne", "Pairing_StoredOther", exchange.Result.Stored.Count)));
            if (exchange.Result.Failed.Count > 0)
            {
                _content.Children.Add(Small(Loc.Plural("Pairing_FailedOne", "Pairing_FailedOther", exchange.Result.Failed.Count)));
                foreach (var failure in exchange.Result.Failed) _content.Children.Add(Small("· " + failure.Reason));
            }
            switch (_followUp)
            {
                case HandoffFollowUp.PickFolder folder:
                    _content.Children.Add(Text(Loc.Format("Sync_FolderRelink", folder.Space)));
                    break;
                case HandoffFollowUp.GoogleSignIn google:
                    _content.Children.Add(Text(Loc.Format("Sync_GoogleRelink", google.Hint ?? "", google.Space)));
                    break;
            }
        }
        SetButtons(null, "Common_OK");
    }

    private void ShowFailed(Exception error)
    {
        _step = Step.Done;
        _renew?.Stop();
        _content.Children.Clear();
        _content.Children.Add(Text(Message(error)));
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
