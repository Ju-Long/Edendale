using System.Globalization;
using Edendale.Windows.Models;

namespace Edendale.Windows.Core;

internal static class ReleaseCalendar
{
    /// <summary>Abbreviated month name, 1-based, from the string catalogue.</summary>
    private static string MonthName(int month) => AppText.Get($"Month_{month}");

    public static ReleaseYearGrid CreateYearGrid(int year)
    {
        if (year is < 1 or > 9999)
        {
            throw new ArgumentOutOfRangeException(nameof(year), "Year must be between 1 and 9999.");
        }

        var grid = new ReleaseYearGrid { Year = year };
        var slots = EmptyWeek();
        string? monthLabel = MonthName(1);

        for (var date = new DateOnly(year, 1, 1); date.Year == year; date = date.AddDays(1))
        {
            if (date.Day == 1 && date.Month > 1) monthLabel = MonthName(date.Month);

            var dayOfWeek = (int)date.DayOfWeek;
            slots[dayOfWeek] = new ReleaseDaySlot
            {
                DateKey = date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                Year = date.Year,
                Month = date.Month,
                Day = date.Day,
                IsFirstOfMonth = date.Day == 1,
            };

            if (dayOfWeek != (int)DayOfWeek.Saturday) continue;
            grid.Columns.Add(new ReleaseWeekColumn { MonthLabel = monthLabel, Slots = slots });
            slots = EmptyWeek();
            monthLabel = null;
        }

        if (slots.Any(slot => slot is not null))
        {
            grid.Columns.Add(new ReleaseWeekColumn { MonthLabel = monthLabel, Slots = slots });
        }
        return grid;
    }

    public static string SelectionSummary(string from, string to)
    {
        if (!DateOnly.TryParseExact(
                from, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var start)
            || !DateOnly.TryParseExact(
                to, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var end))
        {
            return "";
        }

        var dayCount = end.DayNumber - start.DayNumber + 1;
        var countText = AppText.Plural("Plural_DayOne", "Plural_DayOther", dayCount);
        // dayCount is 1 here, so countText already reads "1 day".
        if (start == end) return $"{start.Day} {Month(start)} {start.Year} · {countText}";
        if (start.Year != end.Year)
        {
            return $"{start.Day} {Month(start)} {start.Year} – " +
                $"{end.Day} {Month(end)} {end.Year} · {countText}";
        }
        if (start.Month != end.Month)
        {
            return $"{start.Day} {Month(start)} – {end.Day} {Month(end)} " +
                $"{start.Year} · {countText}";
        }
        return $"{start.Day} – {end.Day} {Month(start)} {start.Year} · {countText}";
    }

    private static string Month(DateOnly value) => MonthName(value.Month);

    private static List<ReleaseDaySlot?> EmptyWeek() =>
        [null, null, null, null, null, null, null];
}
