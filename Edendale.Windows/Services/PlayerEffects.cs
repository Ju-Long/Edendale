// Applies the device-local audio and picture preferences to a LibVLC media
// player, and builds the LibVLC instance arguments that subtitle appearance
// and video enhancement need. LibVLC's equalizer and adjust filter can change
// live; freetype and d3d11 options are read when LibVLC opens the video
// output, so those arrive through the instance (and a reopen, F.3).

using Edendale.Windows.Core;
using LibVLCSharp.Shared;
using LibVLCSharp.Shared.Structures;

namespace Edendale.Windows.Services;

internal static class PlayerEffects
{
    /// <summary>
    /// 3.6: the profile plus user adjustments plus the booster, or no
    /// equalizer at all when everything is 0.
    /// </summary>
    public static void ApplyEqualizer(MediaPlayer player, AudioEnhancement audio)
    {
        if (audio.IsEffectivelyFlat)
        {
            player.UnsetEqualizer();
            return;
        }

        using var equalizer = new Equalizer();
        equalizer.SetPreamp((float)audio.EffectivePreamp);
        var bands = audio.EffectiveBands;
        for (var band = 0; band < AudioEnhancementProfiles.BandCount && band < bands.Count; band++)
        {
            equalizer.SetAmp((float)bands[band], (uint)band);
        }
        // LibVLC copies the settings, so the equalizer can be released here.
        player.SetEqualizer(equalizer);
    }

    /// <summary>3.7: LibVLC's adjust filter, switched off for neutral values.</summary>
    public static void ApplyAdjustments(MediaPlayer player, VideoAdjustmentValues values)
    {
        if (values.IsNeutral)
        {
            player.SetAdjustInt(VideoAdjustOption.Enable, 0);
            return;
        }

        player.SetAdjustInt(VideoAdjustOption.Enable, 1);
        player.SetAdjustFloat(VideoAdjustOption.Brightness, (float)values.Brightness);
        player.SetAdjustFloat(VideoAdjustOption.Contrast, (float)values.Contrast);
        player.SetAdjustFloat(VideoAdjustOption.Gamma, (float)values.Gamma);
        player.SetAdjustFloat(VideoAdjustOption.Saturation, (float)values.Saturation);
        player.SetAdjustFloat(VideoAdjustOption.Hue, (float)VideoAdjustmentValues.LibVlcHue(values.Hue));
    }

    /// <summary>
    /// The extra LibVLC instance arguments: subtitle appearance (3.8), video
    /// enhancement (E.2), and headphone surround (X.3). The order is fixed,
    /// so equal settings give equal lists and the instance is reused.
    /// </summary>
    public static IReadOnlyList<string> EngineArguments(VideoEnhancementResult enhancement, double textScaleFactor)
    {
        var arguments = new List<string>();
        arguments.AddRange(AppServices.SubtitleAppearance.LibVlcArguments(textScaleFactor));
        arguments.AddRange(enhancement.Arguments);
        if (AppServices.PlayerPreferences.HeadphoneSurround)
        {
            // LibVLC's binaural renderer with the HRTF set the build ships in hrtfs/.
            arguments.Add("--spatialaudio-headphones");
        }
        return arguments;
    }

    /// <summary>Joins LibVLC's track descriptions with the media's track list.</summary>
    public static (IReadOnlyList<PlayerTrack> Video, IReadOnlyList<PlayerTrack> Audio, IReadOnlyList<PlayerTrack> Subtitles) Tracks(
        MediaPlayer player,
        PlayerContext? context)
    {
        MediaTrack[] mediaTracks;
        using (var media = player.Media)
        {
            mediaTracks = media?.Tracks ?? [];
        }

        PlayerTrack Join(TrackDescription description, TrackType type)
        {
            var match = mediaTracks.FirstOrDefault(track => track.TrackType == type && track.Id == description.Id);
            var known = match.TrackType == type && match.Id == description.Id;
            return new PlayerTrack
            {
                Id = description.Id,
                Name = known && !string.IsNullOrWhiteSpace(match.Description) ? match.Description : (known ? null : description.Name),
                Language = known ? match.Language : null,
                Width = known && type == TrackType.Video ? (int)match.Data.Video.Width : 0,
                Height = known && type == TrackType.Video ? (int)match.Data.Video.Height : 0,
                Channels = known && type == TrackType.Audio ? (int)match.Data.Audio.Channels : 0,
                IsExternal = type == TrackType.Text && context?.IsExternalSubtitle(description.Id) == true,
            };
        }

        return (
            [.. player.VideoTrackDescription.Where(track => track.Id >= 0).Select(track => Join(track, TrackType.Video))],
            [.. player.AudioTrackDescription.Where(track => track.Id >= 0).Select(track => Join(track, TrackType.Audio))],
            [.. player.SpuDescription.Where(track => track.Id >= 0).Select(track => Join(track, TrackType.Text))]);
    }

    /// <summary>The video track's visible size, frame rate, and pixel aspect ratio, once LibVLC knows them.</summary>
    public static VideoSourceInfo SourceInfo(Media? media)
    {
        if (media is null) return VideoSourceInfo.Unknown;
        foreach (var track in media.Tracks)
        {
            if (track.TrackType != TrackType.Video) continue;
            var video = track.Data.Video;
            double? rate = video.FrameRateDen > 0 && video.FrameRateNum > 0
                ? (double)video.FrameRateNum / video.FrameRateDen
                : null;
            var square = video.SarNum == 0 || video.SarDen == 0;
            return new VideoSourceInfo((int)video.Width, (int)video.Height, rate,
                square ? 1 : video.SarNum, square ? 1 : video.SarDen);
        }
        return VideoSourceInfo.Unknown;
    }
}
