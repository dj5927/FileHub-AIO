namespace NASMount;

/// <summary>
/// Unconnected profile deletion is independent of DRIVE LETTER OCCUPANCY.
/// The same letter may be owned by some other profile or vendor. Block only
/// if THIS profile has a tracked mount or verified active owned mapping.
/// Additional old-process/WinFsp bridge probes run before actual deletion.
/// </summary>
internal static class ProfileDeletionPolicy
{
    internal static bool IsBlocked(bool mounted, bool managedRecord,
        bool activeOwnedMapping) => mounted || managedRecord || activeOwnedMapping;

    internal static void SelfTest()
    {
        static void Verify(bool ok, string name)
        {
            if (!ok) throw new InvalidOperationException(
                "PROFILE_DELETE_POLICY_FAIL " + name);
        }
        Verify(!IsBlocked(false, false, false),
            "Unauthenticated OneDrive profile is removable even when some other drive letter is occupied");
        Verify(IsBlocked(true, false, false), "A live mounted drive is protected");
        Verify(IsBlocked(false, true, false), "A tracked backend record is protected");
        Verify(IsBlocked(false, false, true), "A verified owned mapping is protected");
        Verify(IsBlocked(true, true, true), "All active ownership conditions protect");
        var oneDrive = new NasProfile {
            Protocol = "OneDrive", Letter = "U",
            Id = Guid.Parse("12345678-aaaa-bbbb-cccc-111122223333")
        };
        Verify(!RemoteMount.IsOurNetworkShare(oneDrive,
            @"\\NASMount-87654321\OtherAccount"),
            "Other NASMount profile owns its own mapping");
        Verify(!RemoteMount.IsOurNetworkShare(oneDrive,
            @"\\RaiDrive-User\Cloud"),
            "Other vendor mapping is not ours");
        Verify(RemoteMount.IsOurNetworkShare(oneDrive,
            @"\\NASMount-12345678\OneDrive"),
            "Only exact profile UUID mapping is ours");
        Console.WriteLine("PROFILE_DELETE_UNAUTHENTICATED_FOREIGN_LETTER_POLICY_PASS");
    }
}
