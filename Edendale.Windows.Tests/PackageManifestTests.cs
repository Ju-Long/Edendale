using System.Xml.Linq;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// What the MSIX manifest must declare for features that only a packaged
/// build gets from it: the edendale:// protocol (pairing links and jump-list
/// routes) and the inbound firewall rule the pairing screen's listener needs
/// (the Windows counterpart of apple-27.1's macOS incoming-connections fix).
/// </summary>
[TestClass]
public sealed class PackageManifestTests
{
    private static readonly XNamespace Uap = "http://schemas.microsoft.com/appx/manifest/uap/windows10";
    private static readonly XNamespace Desktop2 = "http://schemas.microsoft.com/appx/manifest/desktop/windows10/2";

    private static XDocument Manifest()
    {
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory); directory is not null; directory = directory.Parent)
        {
            var path = Path.Combine(directory.FullName, "Edendale.Windows", "Package.appxmanifest");
            if (File.Exists(path)) return XDocument.Load(path);
        }
        Assert.Inconclusive("Package.appxmanifest isn't reachable from the test assembly.");
        return null!;
    }

    [TestMethod]
    public void ThePackageDeclaresTheEdendaleProtocol()
    {
        var protocols = Manifest().Descendants(Uap + "Protocol").Select(protocol => (string?)protocol.Attribute("Name")).ToList();
        CollectionAssert.Contains(protocols, "edendale");
    }

    [TestMethod]
    public void ThePairingListenerHasAnInboundRuleOnPrivateNetworksOnly()
    {
        var rules = Manifest().Descendants(Desktop2 + "FirewallRules")
            .Where(set => (string?)set.Attribute("Executable") == "Edendale.exe")
            .SelectMany(set => set.Elements(Desktop2 + "Rule"))
            .ToList();
        Assert.IsTrue(rules.Any(rule => (string?)rule.Attribute("Direction") == "in" && (string?)rule.Attribute("IPProtocol") == "TCP"
            && (string?)rule.Attribute("Profile") == "private"));
        Assert.IsFalse(rules.Any(rule => (string?)rule.Attribute("Profile") is "public" or "all"), "public networks stay closed");
    }
}
