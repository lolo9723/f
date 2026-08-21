package tr.edu.balikesir.anketrapor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class SimpleXlsxWriter {
    private SimpleXlsxWriter() {}

    static String write(Context context, String filename, String[] headers, List<String[]> rows, int hyperlinkColumn) throws Exception {
        if (filename == null || filename.trim().isEmpty()) filename = "Yerel_Ajan_Sonuc.xlsx";
        if (!filename.toLowerCase().endsWith(".xlsx")) filename += ".xlsx";
        OutputTarget target = openOutput(context, filename);
        try (ZipOutputStream zip = new ZipOutputStream(target.stream)) {
            put(zip, "[Content_Types].xml", contentTypes());
            put(zip, "_rels/.rels", rootRels());
            put(zip, "xl/workbook.xml", workbook());
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels());
            put(zip, "xl/styles.xml", styles());
            SheetData sd = sheet(headers, rows, hyperlinkColumn);
            put(zip, "xl/worksheets/sheet1.xml", sd.xml);
            if (!sd.rels.isEmpty()) put(zip, "xl/worksheets/_rels/sheet1.xml.rels", sd.rels);
        }
        return target.display;
    }

    static List<String[]> rowsFromJson(String json) {
        ArrayList<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < a.length(); i++) {
                JSONArray r = a.optJSONArray(i);
                if (r == null) continue;
                String[] row = new String[r.length()];
                for (int j = 0; j < r.length(); j++) row[j] = r.optString(j, "");
                out.add(row);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static OutputTarget openOutput(Context c, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Yerel Ajan");
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("Downloads kaydı oluşturulamadı");
            OutputStream out = cr.openOutputStream(uri, "w");
            if (out == null) throw new IllegalStateException("Dosya açılamadı");
            return new OutputTarget(out, "İndirilenler/Yerel Ajan/" + name);
        }
        File dir = new File(c.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "Yerel Ajan");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Klasör oluşturulamadı");
        File f = new File(dir, name);
        return new OutputTarget(new FileOutputStream(f), f.getAbsolutePath());
    }

    private static void put(ZipOutputStream z, String name, String text) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static String contentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"+
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"+
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"+
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"+
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"+
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"+
                "</Types>";
    }

    private static String rootRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"+
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"+
                "</Relationships>";
    }

    private static String workbook() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"+
                "<sheets><sheet name=\"Sonuçlar\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
    }

    private static String workbookRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"+
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"+
                "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"+
                "</Relationships>";
    }

    private static String styles() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+
                "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"+
                "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"+
                "<fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills>"+
                "<borders count=\"1\"><border/></borders>"+
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"+
                "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>"+
                "</styleSheet>";
    }

    private static SheetData sheet(String[] headers, List<String[]> rows, int hyperlinkColumn) {
        StringBuilder s = new StringBuilder();
        StringBuilder rel = new StringBuilder();
        ArrayList<String> links = new ArrayList<>();
        s.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        s.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
        s.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
        s.append("<sheetData>");
        s.append("<row r=\"1\">");
        for (int c = 0; c < headers.length; c++) cell(s, c, 1, headers[c], 1);
        s.append("</row>");
        int rr = 2;
        for (String[] row : rows) {
            s.append("<row r=\"").append(rr).append("\">");
            for (int c = 0; c < headers.length; c++) {
                String v = c < row.length ? row[c] : "";
                cell(s, c, rr, v, 0);
                if (c == hyperlinkColumn && isHttp(v)) links.add(cellRef(c, rr) + "\t" + v);
            }
            s.append("</row>");
            rr++;
        }
        s.append("</sheetData>");
        if (!links.isEmpty()) {
            s.append("<hyperlinks>");
            for (int i = 0; i < links.size(); i++) {
                String ref = links.get(i).substring(0, links.get(i).indexOf('\t'));
                s.append("<hyperlink ref=\"").append(ref).append("\" r:id=\"rId").append(i + 1).append("\"/>");
            }
            s.append("</hyperlinks>");
            rel.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for (int i = 0; i < links.size(); i++) {
                String url = links.get(i).substring(links.get(i).indexOf('\t') + 1);
                rel.append("<Relationship Id=\"rId").append(i + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"").append(xml(url)).append("\" TargetMode=\"External\"/>");
            }
            rel.append("</Relationships>");
        }
        s.append("</worksheet>");
        return new SheetData(s.toString(), rel.toString());
    }

    private static void cell(StringBuilder b, int col, int row, String value, int style) {
        b.append("<c r=\"").append(cellRef(col, row)).append("\" t=\"inlineStr\" s=\"").append(style).append("\"><is><t xml:space=\"preserve\">").append(xml(value)).append("</t></is></c>");
    }

    private static String cellRef(int col, int row) {
        StringBuilder c = new StringBuilder();
        int n = col;
        do { c.insert(0, (char)('A' + (n % 26))); n = n / 26 - 1; } while (n >= 0);
        return c + String.valueOf(row);
    }

    private static boolean isHttp(String s) { return s != null && (s.startsWith("https://") || s.startsWith("http://")); }
    private static String xml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static final class OutputTarget {
        final OutputStream stream; final String display;
        OutputTarget(OutputStream stream, String display) { this.stream = stream; this.display = display; }
    }
    private static final class SheetData {
        final String xml; final String rels;
        SheetData(String xml, String rels) { this.xml = xml; this.rels = rels; }
    }
}
