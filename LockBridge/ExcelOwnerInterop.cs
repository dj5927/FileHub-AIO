namespace NASMount.LockBridge;

/// <summary>
/// Microsoft Excel owns the real ~$ workbook owner file, not FileHub.
/// An uncached server-side existence check is required before a NEW editor
/// takes a workbook. No synthetic ~$ payload is ever fabricated.
/// </summary>
internal static class ExcelOwnerInterop
{
    internal static bool IsWorkbook(string path)
    {
        string name = Path.GetFileName(path.Replace('/', '\\'));
        if (name.StartsWith("~$", StringComparison.OrdinalIgnoreCase))
            return false;
        return Path.GetExtension(name).ToLowerInvariant()
            is ".xls" or ".xlsx" or ".xlsm" or ".xlsb";
    }

    internal static bool IsOwnerFile(string path)
    {
        string name = Path.GetFileName(path.Replace('/', '\\'));
        return name.StartsWith("~$", StringComparison.OrdinalIgnoreCase) &&
            Path.GetExtension(name).ToLowerInvariant()
                is ".xls" or ".xlsx" or ".xlsm" or ".xlsb";
    }

    // Office variants replace the first two filename characters with "~$"
    // rather than always prepending "~$". Check both rather than guessing
    // which Excel version/filename rule another mounted client will use.
    internal static string[] PossibleOwners(string document)
    {
        string path = DirectWebDavReadOnlyBackend.Normalize(document);
        string name = path.Split('/').Last();
        string parent = path[..(path.LastIndexOf('/') + 1)];
        var options = new[] {
            parent + "~$" + name,
            parent + "~$" + (name.Length > 2 ? name[2..] : name)
        };
        return options.Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
    }

    internal static void RequireAvailable(
        DirectWebDavReadOnlyBackend backend, string document,
        Func<string, string?, bool>? isOwnerCreatedByThisMount = null)
    {
        if (!IsWorkbook(document)) return;
        foreach (string owner in PossibleOwners(document))
        {
            backend.Invalidate(owner);
            var existing = backend.Stat(owner);
            if (existing is not null &&
                (isOwnerCreatedByThisMount is null ||
                 !isOwnerCreatedByThisMount(owner, existing.ETag)))
                throw new CooperativeLocks.LockConflictException(
                    "Excel owner file exists on NAS; open read-only");
        }
        // Read a legacy FileHub lock as a compatibility safeguard, but
        // NEVER create a new .nasmount.lock for Excel.
        string legacy = DirectWebDavReadOnlyBackend.Normalize(document) +
            ".nasmount.lock";
        backend.Invalidate(legacy);
        if (backend.Stat(legacy) is not null)
            throw new CooperativeLocks.LockConflictException(
                "Earlier FileHub editor still owns this Excel workbook");
    }
}
