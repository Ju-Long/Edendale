using System.Globalization;
using Edendale.Windows.Models;

namespace Edendale.Windows.Core;

internal static class ReleaseCalendar
{
    private static readonly string[] MonthNames =
    [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ];

    public static ReleaseYearGrid CreateYearGrid(int year)
    {
        if (year is < 1 or > 9999)
        {
            throw new ArgumentOutOfRangeException(nameof(year), "Year must be between 1 and 9999.");
        }

        var grid = new ReleaseYearGrid { Year = year };
        var slots = EmptyWeek();
        string? monthLabel = "Jan";

        for (var date = new DateOnly(year, 1, 1); date.Year == year; date = date.AddDays(1))
        {
            if (date.Day == 1 && date.Month > 1) monthLabel = MonthNames[date.Month - 1];

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
        var countText = dayCount == 1 ? "1 day" : $"{dayCount} days";
        if (start == end) return $"{start.Day} {Month(start)} {start.Year} · 1 day";
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

    private static string Month(DateOnly value) => MonthNames[value.Month - 1];

    private static List<ReleaseDaySlot?> EmptyWeek() =>
        [null, null, null, null, null, null, null];
}
