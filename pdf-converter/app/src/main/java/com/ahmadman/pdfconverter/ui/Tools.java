package com.ahmadman.pdfconverter.ui;

import static com.ahmadman.pdfconverter.ui.Tool.Option.choice;
import static com.ahmadman.pdfconverter.ui.Tool.Option.number;
import static com.ahmadman.pdfconverter.ui.Tool.Option.pages;
import static com.ahmadman.pdfconverter.ui.Tool.Option.password;
import static com.ahmadman.pdfconverter.ui.Tool.Option.text;
import static com.ahmadman.pdfconverter.ui.Tool.Option.toggle;

import com.ahmadman.pdfconverter.R;
import com.ahmadman.pdfconverter.convert.FromPdf;
import com.ahmadman.pdfconverter.convert.PdfTools;
import com.ahmadman.pdfconverter.convert.ToPdf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** All tools of the app. */
public final class Tools {

    static final String[] PDF = {"application/pdf"};
    static final String[] IMAGES = {"image/*"};
    static final String[] WORD = {"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-word.document.macroEnabled.12"};
    static final String[] EXCEL = {"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel.sheet.macroEnabled.12"};
    static final String[] POWERPOINT = {"application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.ms-powerpoint.presentation.macroEnabled.12"};
    static final String[] TEXT = {"text/plain"};
    static final String[] HTML = {"text/html", "application/xhtml+xml"};
    static final String[] MARKDOWN = {"text/markdown", "text/x-markdown", "text/plain", "application/octet-stream"};
    static final String[] CSV = {"text/csv", "text/comma-separated-values", "text/tab-separated-values",
            "application/csv", "text/plain"};
    static final String[] ANY_DOCUMENT = {"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/*", "application/xhtml+xml", "application/json", "application/xml", "application/octet-stream"};

    private static List<Tool> all;

    private Tools() {
    }

    private static Tool.Option pageRange() {
        return pages(R.string.opt_pages, R.string.hint_pages);
    }

    private static Tool.Option dpi(String def, String... values) {
        Object[] pairs = new Object[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            pairs[i * 2] = values[i];
            switch (values[i]) {
                case "72":
                    pairs[i * 2 + 1] = R.string.dpi_72;
                    break;
                case "100":
                    pairs[i * 2 + 1] = R.string.dpi_100;
                    break;
                case "150":
                    pairs[i * 2 + 1] = R.string.dpi_150;
                    break;
                case "200":
                    pairs[i * 2 + 1] = R.string.dpi_200;
                    break;
                default:
                    pairs[i * 2 + 1] = R.string.dpi_300;
            }
        }
        return choice("dpi", R.string.opt_quality_dpi, def, pairs);
    }

    private static Tool.Option pageSize(String def) {
        return choice("page", R.string.opt_page_size, def,
                "a4", R.string.page_a4, "a4l", R.string.page_a4l, "letter", R.string.page_letter);
    }

    private static Tool.Option margin(String def) {
        return choice("margin", R.string.opt_margin, def,
                "small", R.string.margin_small, "normal", R.string.margin_normal, "large", R.string.margin_large);
    }

    private static Tool.Option fontSize() {
        return choice("fontsize", R.string.opt_font_size, "11",
                "9", R.string.font_9, "11", R.string.font_11, "13", R.string.font_13, "16", R.string.font_16);
    }

    public static synchronized List<Tool> all() {
        if (all != null) {
            return all;
        }
        List<Tool> t = new ArrayList<>();
        // --------------------------------------------------------------- from PDF
        t.add(new Tool("pdf_docx", Tool.FROM_PDF, R.string.t_pdf_docx, R.string.d_pdf_docx, "DOCX", 0xFF2B579A,
                PDF, false, FromPdf::toWord,
                choice("mode", R.string.opt_mode, "text",
                        "text", R.string.mode_docx_text, "image", R.string.mode_docx_image),
                pageRange()).note(R.string.n_pdf_docx));
        t.add(new Tool("pdf_xlsx", Tool.FROM_PDF, R.string.t_pdf_xlsx, R.string.d_pdf_xlsx, "XLSX", 0xFF217346,
                PDF, false, FromPdf::toExcel,
                choice("mode", R.string.opt_sheets, "sheets",
                        "sheets", R.string.mode_xlsx_sheets, "single", R.string.mode_xlsx_single),
                pageRange()).note(R.string.n_pdf_text));
        t.add(new Tool("pdf_pptx", Tool.FROM_PDF, R.string.t_pdf_pptx, R.string.d_pdf_pptx, "PPTX", 0xFFD24726,
                PDF, false, FromPdf::toPowerPoint, dpi("150", "100", "150", "200"), pageRange()));
        t.add(new Tool("pdf_img", Tool.FROM_PDF, R.string.t_pdf_img, R.string.d_pdf_img, "JPG", 0xFF7C3AED,
                PDF, false, FromPdf::toImages,
                choice("format", R.string.opt_format, "jpg", "jpg", R.string.fmt_jpg, "png", R.string.fmt_png,
                        "webp", R.string.fmt_webp),
                dpi("150", "72", "150", "200", "300"),
                toggle("zip", R.string.opt_zip, false), pageRange()));
        t.add(new Tool("pdf_long", Tool.FROM_PDF, R.string.t_pdf_long, R.string.d_pdf_long, "IMG", 0xFF0D9488,
                PDF, false, FromPdf::toLongImage,
                choice("format", R.string.opt_format, "jpg", "jpg", R.string.fmt_jpg, "png", R.string.fmt_png),
                dpi("100", "72", "100", "150"), pageRange()));
        t.add(new Tool("pdf_txt", Tool.FROM_PDF, R.string.t_pdf_txt, R.string.d_pdf_txt, "TXT", 0xFF52606D,
                PDF, false, FromPdf::toText, pageRange()).note(R.string.n_pdf_text));
        t.add(new Tool("pdf_html", Tool.FROM_PDF, R.string.t_pdf_html, R.string.d_pdf_html, "HTML", 0xFFE34F26,
                PDF, false, FromPdf::toHtml, pageRange()).note(R.string.n_pdf_text));
        t.add(new Tool("pdf_epub", Tool.FROM_PDF, R.string.t_pdf_epub, R.string.d_pdf_epub, "EPUB", 0xFF8B5CF6,
                PDF, false, FromPdf::toEpub, pageRange()).note(R.string.n_pdf_text));
        t.add(new Tool("pdf_csv", Tool.FROM_PDF, R.string.t_pdf_csv, R.string.d_pdf_csv, "CSV", 0xFF15803D,
                PDF, false, FromPdf::toCsv,
                choice("delimiter", R.string.opt_delimiter, ";", ";", R.string.delim_semicolon, ",", R.string.delim_comma),
                pageRange()).note(R.string.n_pdf_text));
        t.add(new Tool("pdf_md", Tool.FROM_PDF, R.string.t_pdf_md, R.string.d_pdf_md, "MD", 0xFF1F2937,
                PDF, false, FromPdf::toMarkdown, pageRange()).note(R.string.n_pdf_text));
        t.add(new Tool("pdf_rtf", Tool.FROM_PDF, R.string.t_pdf_rtf, R.string.d_pdf_rtf, "RTF", 0xFF475569,
                PDF, false, FromPdf::toRtf, pageRange()).note(R.string.n_pdf_text));
        t.add(new Tool("pdf_images", Tool.FROM_PDF, R.string.t_pdf_images, R.string.d_pdf_images, "PNG", 0xFFDB2777,
                PDF, false, FromPdf::extractImages, toggle("zip", R.string.opt_zip, false), pageRange()));

        // --------------------------------------------------------------- to PDF
        t.add(new Tool("img_pdf", Tool.TO_PDF, R.string.t_img_pdf, R.string.d_img_pdf, "IMG", 0xFF7C3AED,
                IMAGES, true, ToPdf::images,
                choice("page", R.string.opt_page_size, "auto", "auto", R.string.page_auto, "a4", R.string.page_a4,
                        "fit", R.string.page_fit, "letter", R.string.page_letter),
                choice("margin", R.string.opt_margin, "small", "none", R.string.margin_none,
                        "small", R.string.margin_small, "large", R.string.margin_large),
                choice("quality", R.string.opt_image_quality, "original", "original", R.string.quality_original,
                        "compressed", R.string.quality_compressed)));
        t.add(new Tool("docx_pdf", Tool.TO_PDF, R.string.t_docx_pdf, R.string.d_docx_pdf, "DOCX", 0xFF2B579A,
                WORD, false, ToPdf::document, pageSize("a4"), margin("normal")).note(R.string.n_office));
        t.add(new Tool("xlsx_pdf", Tool.TO_PDF, R.string.t_xlsx_pdf, R.string.d_xlsx_pdf, "XLSX", 0xFF217346,
                EXCEL, false, ToPdf::document, pageSize("a4l"), margin("small")).note(R.string.n_office));
        t.add(new Tool("pptx_pdf", Tool.TO_PDF, R.string.t_pptx_pdf, R.string.d_pptx_pdf, "PPTX", 0xFFD24726,
                POWERPOINT, false, ToPdf::document).note(R.string.n_office));
        t.add(new Tool("txt_pdf", Tool.TO_PDF, R.string.t_txt_pdf, R.string.d_txt_pdf, "TXT", 0xFF52606D,
                TEXT, false, ToPdf::document, pageSize("a4"), margin("normal"), fontSize()));
        t.add(new Tool("html_pdf", Tool.TO_PDF, R.string.t_html_pdf, R.string.d_html_pdf, "HTML", 0xFFE34F26,
                HTML, false, ToPdf::document, pageSize("a4"), margin("normal"), fontSize()));
        t.add(new Tool("md_pdf", Tool.TO_PDF, R.string.t_md_pdf, R.string.d_md_pdf, "MD", 0xFF1F2937,
                MARKDOWN, false, ToPdf::document, pageSize("a4"), margin("normal"), fontSize()));
        t.add(new Tool("csv_pdf", Tool.TO_PDF, R.string.t_csv_pdf, R.string.d_csv_pdf, "CSV", 0xFF15803D,
                CSV, false, ToPdf::document, pageSize("a4l"), margin("small"), fontSize()));
        t.add(new Tool("any_pdf", Tool.TO_PDF, R.string.t_any_pdf, R.string.d_any_pdf, "AUTO", 0xFF0EA5E9,
                ANY_DOCUMENT, false, ToPdf::document, pageSize("a4"), margin("normal"), fontSize())
                .note(R.string.n_any));

        // --------------------------------------------------------------- PDF tools
        t.add(new Tool("merge", Tool.PDF_TOOLS, R.string.t_merge, R.string.d_merge, "⊕", 0xFFE5322D,
                PDF, true, PdfTools::merge).note(R.string.n_merge));
        t.add(new Tool("split", Tool.PDF_TOOLS, R.string.t_split, R.string.d_split, "✂", 0xFFF97316,
                PDF, false, PdfTools::split,
                choice("mode", R.string.opt_split_mode, "each", "each", R.string.split_each,
                        "every", R.string.split_every, "ranges", R.string.split_ranges),
                number("every", R.string.opt_every, "2").when("mode", "every"),
                text("ranges", R.string.opt_ranges, R.string.hint_ranges, "").when("mode", "ranges"),
                toggle("zip", R.string.opt_zip, false)));
        t.add(new Tool("extract", Tool.PDF_TOOLS, R.string.t_extract, R.string.d_extract, "1·3", 0xFF2563EB,
                PDF, false, PdfTools::extract,
                pages(R.string.opt_pages_order, R.string.hint_pages_order),
                toggle("reverse", R.string.opt_reverse, false)));
        t.add(new Tool("delete", Tool.PDF_TOOLS, R.string.t_delete, R.string.d_delete, "−", 0xFFDC2626,
                PDF, false, PdfTools::delete, pages(R.string.opt_pages_delete, R.string.hint_pages)));
        t.add(new Tool("rotate", Tool.PDF_TOOLS, R.string.t_rotate, R.string.d_rotate, "↻", 0xFF0891B2,
                PDF, false, PdfTools::rotate,
                choice("angle", R.string.opt_angle, "90", "90", R.string.angle_90, "180", R.string.angle_180,
                        "270", R.string.angle_270),
                pageRange()));
        t.add(new Tool("compress", Tool.PDF_TOOLS, R.string.t_compress, R.string.d_compress, "ZIP", 0xFF16A34A,
                PDF, false, PdfTools::compress,
                choice("level", R.string.opt_level, "medium", "low", R.string.level_low,
                        "medium", R.string.level_medium, "high", R.string.level_high)).note(R.string.n_compress));
        t.add(new Tool("protect", Tool.PDF_TOOLS, R.string.t_protect, R.string.d_protect, "🔒", 0xFF334155,
                PDF, false, PdfTools::protect,
                password("password", R.string.opt_password), password("password2", R.string.opt_password2),
                toggle("allowPrint", R.string.opt_allow_print, true),
                toggle("allowCopy", R.string.opt_allow_copy, true),
                toggle("allowEdit", R.string.opt_allow_edit, false)).note(R.string.n_protect));
        t.add(new Tool("unlock", Tool.PDF_TOOLS, R.string.t_unlock, R.string.d_unlock, "🔓", 0xFF64748B,
                PDF, false, PdfTools::unlock, password("currentPassword", R.string.opt_current_password))
                .note(R.string.n_unlock));
        t.add(new Tool("watermark", Tool.PDF_TOOLS, R.string.t_watermark, R.string.d_watermark, "WM", 0xFF9333EA,
                PDF, false, PdfTools::watermark,
                text("text", R.string.opt_watermark_text, R.string.hint_watermark, ""),
                choice("layout", R.string.opt_layout, "diagonal", "diagonal", R.string.layout_diagonal,
                        "horizontal", R.string.layout_horizontal),
                choice("opacity", R.string.opt_opacity, "25", "15", R.string.opacity_15, "25", R.string.opacity_25,
                        "45", R.string.opacity_45, "70", R.string.opacity_70),
                choice("color", R.string.opt_color, "gray", "gray", R.string.color_gray, "red", R.string.color_red,
                        "blue", R.string.color_blue, "black", R.string.color_black),
                pageRange()));
        t.add(new Tool("numbers", Tool.PDF_TOOLS, R.string.t_numbers, R.string.d_numbers, "#", 0xFF0F766E,
                PDF, false, PdfTools::pageNumbers,
                choice("position", R.string.opt_position, "bottom-center", "bottom-center", R.string.pos_bottom_center,
                        "bottom-right", R.string.pos_bottom_right, "bottom-left", R.string.pos_bottom_left,
                        "top-right", R.string.pos_top_right),
                choice("format", R.string.opt_number_format, "n", "n", R.string.numfmt_n, "n/N", R.string.numfmt_n_of,
                        "page", R.string.numfmt_page),
                number("start", R.string.opt_start_number, "1"), pageRange()));
        t.add(new Tool("gray", Tool.PDF_TOOLS, R.string.t_gray, R.string.d_gray, "B/W", 0xFF111827,
                PDF, false, PdfTools::rasterize,
                choice("color", R.string.opt_output, "gray", "gray", R.string.raster_gray,
                        "color", R.string.raster_color),
                dpi("150", "100", "150", "200"), pageRange()).note(R.string.n_gray));
        all = Collections.unmodifiableList(t);
        return all;
    }

    public static Tool byId(String id) {
        for (Tool t : all()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }
}
