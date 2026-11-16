using Edendale.Windows.Core;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Automation.Provider;
using Microsoft.UI.Xaml.Controls;

namespace Edendale.Windows.Controls;

/// <summary>
/// A slider in whole decibels whose value reads the way Settings writes it
/// ("−8 dB"), as Apple's preamp slider does through its accessibilityValue
/// (AudioEnhancementSection.swift). Screen readers get the text through the
/// Value pattern alongside the slider's usual RangeValue number.
/// </summary>
public sealed partial class DecibelSlider : Slider
{
    protected override void OnValueChanged(double oldValue, double newValue)
    {
        base.OnValueChanged(oldValue, newValue);
        if (FrameworkElementAutomationPeer.FromElement(this) is DecibelSliderAutomationPeer peer
            && AutomationPeer.ListenerExists(AutomationEvents.PropertyChanged))
        {
            peer.RaiseTextValueChanged(oldValue, newValue);
        }
    }

    protected override AutomationPeer OnCreateAutomationPeer() => new DecibelSliderAutomationPeer(this);
}

/// <summary>The slider's peer, adding the Value pattern with the level as text.</summary>
public sealed partial class DecibelSliderAutomationPeer(DecibelSlider owner)
    : SliderAutomationPeer(owner), IValueProvider
{
    private DecibelSlider Slider => (DecibelSlider)Owner;

    protected override object? GetPatternCore(PatternInterface patternInterface) =>
        patternInterface == PatternInterface.Value ? this : base.GetPatternCore(patternInterface);

    bool IValueProvider.IsReadOnly => false;

    string IValueProvider.Value => DecibelLabel.Text(Slider.Value);

    /// <summary>Accepts "+5 dB", "−3 dB", "-3", or "5"; unreadable text changes nothing.</summary>
    void IValueProvider.SetValue(string value)
    {
        if (!Slider.IsEnabled || !DecibelLabel.TryParse(value, out var level)) return;
        Slider.Value = Math.Clamp(
            Math.Round(level, MidpointRounding.AwayFromZero), Slider.Minimum, Slider.Maximum);
    }

    internal void RaiseTextValueChanged(double oldValue, double newValue) =>
        RaisePropertyChangedEvent(
            ValuePatternIdentifiers.ValueProperty, DecibelLabel.Text(oldValue), DecibelLabel.Text(newValue));
}
