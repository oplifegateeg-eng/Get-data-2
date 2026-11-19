package com.example.wagroupsexporter;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes a small .xlsx workbook (bold header, frozen first row, filters, column widths). */
public final class XlsxWriter {
    private XlsxWriter() { }

    public static final class Sheet {
        public final String name;
        public final Object[] header;
        public final List<Object[]> rows;
        public final int[] widths;

        public Sheet(String name, Object[] header, List<Object[]> rows, int[] widths) {
            this.name = name;
            this.header = header;
            this.rows = rows;
            this.widths = widths;
        }
    }

    private static String esc(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (s.length() > 32000) s = s.substring(0, 32000);
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') continue;
            if (c == 0xFFFE || c == 0xFFFF) continue;
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String col(int n) {
        StringBuilder s = new StringBuilder();
        n++;
        while (n > 0) {
            int m = (n - 1) % 26;
            s.insert(0, (char) ('A' + m));
            n = (n - 1) / 26;
        }
        return s.toString();
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static void cell(StringBuilder sb, int c, int r, Object v, boolean header) {
        String ref = col(c) + r;
        if (v instanceof Number && !(v instanceof Double && (((Double) v).isNaN() || ((Double) v).isInfinite()))) {
            sb.append("<c r=\"").append(ref).append("\"><v>").append(v).append("</v></c>");
        } else {
            sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(header ? " s=\"1\"" : "")
                    .append("><is><t xml:space=\"preserve\">").append(esc(v)).append("</t></is></c>");
        }
    }

    private static void sheet(ZipOutputStream z, Sheet s) throws IOException {
        Writer w = new OutputStreamWriter(z, StandardCharsets.UTF_8);
        int cols = s.header.length;
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        w.write("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
        w.write("<cols>");
        for (int i = 0; i < cols; i++) {
            int width = (s.widths != null && i < s.widths.length) ? s.widths[i] : 18;
            w.write("<col min=\"" + (i + 1) + "\" max=\"" + (i + 1) + "\" width=\"" + width + "\" customWidth=\"1\"/>");
        }
        w.write("</cols><sheetData>");
        StringBuilder sb = new StringBuilder();
        sb.append("<row r=\"1\">");
        for (int i = 0; i < cols; i++) cell(sb, i, 1, s.header[i], true);
        sb.append("</row>");
        w.write(sb.toString());
        int r = 2;
        for (Object[] row : s.rows) {
            sb.setLength(0);
            sb.append("<row r=\"").append(r).append("\">");
            for (int i = 0; i < row.length; i++) cell(sb, i, r, row[i], false);
            sb.append("</row>");
            w.write(sb.toString());
            r++;
        }
        w.write("</sheetData><autoFilter ref=\"A1:" + col(cols - 1) + (r - 1) + "\"/></worksheet>");
        w.flush();
    }

    public static void write(OutputStream os, List<Sheet> sheets) throws IOException {
        String h = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
        ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(os, 1 << 16));
        z.setLevel(6);
        StringBuilder ct = new StringBuilder(h);
        ct.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 0; i < sheets.size(); i++) {
            ct.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        ct.append("</Types>");
        put(z, "[Content_Types].xml", ct.toString());
        put(z, "_rels/.rels", h + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        StringBuilder wb = new StringBuilder(h);
        wb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        StringBuilder rels = new StringBuilder(h);
        rels.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < sheets.size(); i++) {
            wb.append("<sheet name=\"").append(esc(sheets.get(i).name)).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(i + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(i + 1).append(".xml\"/>");
        }
        wb.append("</sheets></workbook>");
        rels.append("<Relationship Id=\"rId").append(sheets.size() + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
        put(z, "xl/workbook.xml", wb.toString());
        put(z, "xl/_rels/workbook.xml.rels", rels.toString());
        put(z, "xl/styles.xml", h + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
        for (int i = 0; i < sheets.size(); i++) {
            z.putNextEntry(new ZipEntry("xl/worksheets/sheet" + (i + 1) + ".xml"));
            sheet(z, sheets.get(i));
            z.closeEntry();
        }
        z.finish();
        z.flush();
    }
}
