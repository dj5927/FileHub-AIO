using System.IO.Compression;
using System.Security;
using System.Text;
using System.Xml.Linq;

namespace NASMount;

/// <summary>
/// Tiny real Office Open XML workbook for an Office-in-use indicator.
/// The .xlsx extension is deliberate: Windows Explorer uses the genuine
/// Excel icon and the status itself can safely be opened in Excel.
/// </summary>
internal static class OfficeMarkerWorkbook
{
    private const string Header = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private const string SpreadsheetNs = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    internal static byte[] Create(CooperativeLocks.Lease lease)
    {
        var details = new (string Label, string Value)[] {
            ("상태", "다른 사용자가 편집 중"),
            ("파일", lease.Resource),
            ("사용자 / PC", lease.Owner),
            ("잠금 시작 (UTC)", lease.CreatedUtc.ToString("u")),
            ("잠금 ID", lease.Id),
            ("안내", "FileHub AIO 사용 중 표시. 원본 Excel 파일은 별도로 존재합니다."),
        };
        static string Cell(string column, int row, string value) =>
            "<c r=\"" + column + row + "\" t=\"inlineStr\"><is><t>" +
            SecurityElement.Escape(value) + "</t></is></c>";

        string rows = string.Concat(details.Select((detail, i) =>
            "<row r=\"" + (i + 1) + "\">" +
            Cell("A", i + 1, detail.Label) +
            Cell("B", i + 1, detail.Value) + "</row>"));

        const string relationshipsNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        const string packageRelationships = "http://schemas.openxmlformats.org/package/2006/relationships";
        using var output = new MemoryStream();
        using (var zip = new ZipArchive(output, ZipArchiveMode.Create, leaveOpen: true))
        {
            Add(zip, "[Content_Types].xml", Header +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "</Types>");
            Add(zip, "_rels/.rels", Header + "<Relationships xmlns=\"" +
                packageRelationships + "\"><Relationship Id=\"rId1\" Type=\"" +
                relationshipsNs + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            Add(zip, "xl/workbook.xml", Header + "<workbook xmlns=\"" +
                SpreadsheetNs + "\" xmlns:r=\"" + relationshipsNs + "\">" +
                "<sheets><sheet name=\"FileHub AIO 사용 중\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            Add(zip, "xl/_rels/workbook.xml.rels", Header + "<Relationships xmlns=\"" +
                packageRelationships + "\"><Relationship Id=\"rId1\" Type=\"" +
                relationshipsNs + "/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            Add(zip, "xl/worksheets/sheet1.xml", Header +
                "<worksheet xmlns=\"" + SpreadsheetNs + "\">" +
                "<dimension ref=\"A1:B6\"/>" +
                "<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>" +
                "<sheetFormatPr defaultRowHeight=\"15\"/>" +
                "<cols><col min=\"1\" max=\"1\" width=\"19\" customWidth=\"1\"/>" +
                "<col min=\"2\" max=\"2\" width=\"78\" customWidth=\"1\"/></cols>" +
                "<sheetData>" + rows + "</sheetData></worksheet>");
        }
        return output.ToArray();
    }

    private static void Add(ZipArchive zip, string name, string contents)
    {
        var entry = zip.CreateEntry(name, CompressionLevel.Fastest);
        using var writer = new StreamWriter(entry.Open(), new UTF8Encoding(false));
        writer.Write(contents);
    }

    internal static string? ReadLeaseId(byte[] bytes)
    {
        if (bytes.Length is < 300 or > 128 * 1024) return null;
        try
        {
            using var stream = new MemoryStream(bytes, writable: false);
            using var zip = new ZipArchive(stream, ZipArchiveMode.Read);
            var sheet = zip.GetEntry("xl/worksheets/sheet1.xml");
            if (sheet is null || sheet.Length > 32 * 1024) return null;
            using var xml = sheet.Open();
            var root = XDocument.Load(xml);
            XNamespace ns = SpreadsheetNs;
            foreach (var row in root.Descendants(ns + "row"))
            {
                string? label = row.Elements(ns + "c")
                    .FirstOrDefault(c => (string?)c.Attribute("r") == "A5")?
                    .Descendants(ns + "t").FirstOrDefault()?.Value;
                if (label != "잠금 ID") continue;
                return row.Elements(ns + "c")
                    .FirstOrDefault(c => (string?)c.Attribute("r") == "B5")?
                    .Descendants(ns + "t").FirstOrDefault()?.Value;
            }
        }
        catch (InvalidDataException) { }
        catch (System.Xml.XmlException) { }
        return null;
    }
}
