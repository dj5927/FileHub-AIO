namespace NASMount;

/// <summary>
/// Automatically assigns the highest unoccupied local drive letter.
/// Reservations from every saved profile are honored, including disconnected
/// profiles; a temporary WinFsp backend letter is also reported as occupied
/// by Windows and cannot be selected.
/// </summary>
internal static class DriveLetterAllocator
{
    internal static string? HighestFree(
        IEnumerable<NasProfile> profiles, Guid? excluding = null,
        IEnumerable<string>? occupiedOverride = null)
    {
        var reserved = profiles
            .Where(p => p.Id != excluding)
            .Select(p => Normalize(p.Letter))
            .Where(s => s.Length == 1)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        var occupied = (occupiedOverride ?? Environment.GetLogicalDrives())
            .Select(Normalize)
            .Where(s => s.Length == 1)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);

        for (char c = 'Z'; c >= 'D'; c--)
        {
            string letter = c.ToString();
            if (!reserved.Contains(letter) && !occupied.Contains(letter))
                return letter;
        }
        return null;
    }

    private static string Normalize(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return "";
        char ch = char.ToUpperInvariant(text.Trim()[0]);
        return ch is >= 'A' and <= 'Z' ? ch.ToString() : "";
    }
}
