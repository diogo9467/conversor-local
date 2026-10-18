using System.Security.AccessControl;
using System.Security.Principal;
using Conversor.Core;

var app = Path.GetFullPath(args[0]);
var fixture = Path.GetFullPath(args[1]);
var root = Path.Combine(Environment.CurrentDirectory, "test-results", "windows-guards-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(root);
var runner = new ConversionRunner(app);
try
{
    var source = Path.Combine(root, "Cancelamento e permissão.pdf");
    File.Copy(fixture, source);
    using var cancellation = new CancellationTokenSource(TimeSpan.FromMilliseconds(350));
    var cancelled = await runner.ConvertAsync(new(source, "pptx"), null, cancellation.Token);
    if (!cancelled.Cancelled || cancelled.Output != null || Directory.GetFiles(root).Length != 1)
        throw new Exception("Cancellation must leave only the original.");
    Console.WriteLine("PASS cancellation stops worker and publishes nothing");

    var directory = new DirectoryInfo(root);
    var originalAcl = directory.GetAccessControl();
    var restricted = directory.GetAccessControl();
    var sid = WindowsIdentity.GetCurrent().User!;
    restricted.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.CreateFiles, AccessControlType.Deny));
    try
    {
        directory.SetAccessControl(restricted);
        var denied = await runner.ConvertAsync(new(source, "pptx"), null, CancellationToken.None);
        if (denied.Success || denied.Output != null || !denied.Error!.Contains("permissão"))
            throw new Exception("Write denied must produce a clear error without another output location.");
        Console.WriteLine("PASS Windows ACL write denial gives explicit error without fallback");
    }
    finally { directory.SetAccessControl(originalAcl); }
    if (Directory.GetFiles(root).Length != 1) throw new Exception("Unexpected output after failure.");
    Console.WriteLine("2 Windows guard assertions passed.");
    return 0;
}
finally { Directory.Delete(root, true); }
