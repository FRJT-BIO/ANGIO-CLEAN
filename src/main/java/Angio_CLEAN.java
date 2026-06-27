import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.measure.ResultsTable;
import ij.plugin.ImageCalculator;
import ij.plugin.PlugIn;
import ij.plugin.ContrastEnhancer;
import ij.plugin.filter.BackgroundSubtracter;
import ij.plugin.filter.GaussianBlur;
import ij.plugin.filter.RankFilters;
import ij.process.ImageConverter;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Angio-CLEAN  —  Batch background-flattening for angiography / vascular images.
 *
 * <p>Java plugin for ImageJ/Fiji. It removes uneven background illumination by
 * subtracting an estimated background from each image, enhancing thin vascular
 * structures. It is the successor of the original {@code Angio-CLEAN.ijm} macro;
 * with the <em>default</em> settings the numerical result is identical to that
 * macro (8-bit → Gaussian blur σ=30 → subtract in 32-bit → RGB).</p>
 *
 * <h2>Algorithm</h2>
 * For each image (or each slice of a stack):
 * <ol>
 *   <li>(optional) convert to 8-bit;</li>
 *   <li>estimate the background with one of: <b>Gaussian blur</b> (default),
 *       <b>rolling ball</b>, or <b>median</b> filter;</li>
 *   <li>compute {@code result = image − background} as 32-bit float;</li>
 *   <li>(optional) invert, convert to the requested output type, and apply an
 *       optional contrast stretch;</li>
 *   <li>save in the chosen format.</li>
 * </ol>
 *
 * <h2>Features for reproducible science</h2>
 * <ul>
 *   <li>Macro-recordable / scriptable (works headless).</li>
 *   <li>Every output image embeds the exact parameters used (TIFF metadata).</li>
 *   <li>A machine-readable <b>CSV report</b> and a human-readable <b>run log</b>
 *       record version, environment, parameters and per-file statistics.</li>
 *   <li>Multi-threaded batch processing; results are independent of thread count.</li>
 *   <li>Per-file error isolation: one bad file never aborts the batch.</li>
 *   <li>Optional preview of the first image before committing to the batch.</li>
 * </ul>
 *
 * @author  Angio-CLEAN contributors
 * @version 3.0
 */
public class Angio_CLEAN implements PlugIn {

    public static final String VERSION = "3.0";

    /* ----------------------------- choices ---------------------------- */
    static final String M_GAUSSIAN = "Gaussian blur";
    static final String M_ROLLING  = "Rolling ball";
    static final String M_MEDIAN   = "Median";
    static final String[] METHODS  = {M_GAUSSIAN, M_ROLLING, M_MEDIAN};

    static final String R_RGB  = "RGB (like original macro)";
    static final String R_32   = "32-bit (raw subtract)";
    static final String R_8    = "8-bit";
    static final String R_SAME = "Same as input";
    static final String[] RESULT_TYPES = {R_RGB, R_32, R_8, R_SAME};

    static final String[] FORMATS = {"TIFF", "PNG", "JPEG"};

    static final String[] IMAGE_EXT = {
            ".tif", ".tiff", ".jpg", ".jpeg", ".png", ".bmp", ".gif"
    };

    /* --------------------------- parameters --------------------------- */
    /** Immutable-ish container for all processing settings. */
    public static class Params {
        public String inputDir   = "";
        public String outputDir  = "";
        public String method     = M_GAUSSIAN;
        public double radius      = 30.0;   // sigma (Gaussian) or radius (others)
        public boolean to8bit     = true;
        public boolean invert     = false;
        public String resultType  = R_RGB;
        public double saturated   = 0.0;    // % saturated pixels for contrast (0 = off)
        public String format      = "TIFF";
        public String suffix      = "_processed";
        public boolean stripExt   = true;
        public boolean recursive  = false;
        public boolean skipExist  = false;
        public boolean doStacks   = true;
        public int threads        = Math.max(1, Runtime.getRuntime().availableProcessors());
        public boolean csvReport  = true;
        public boolean runLog     = true;
        public boolean embedMeta  = true;
    }

    /* ------------------------- preference keys ------------------------ */
    private static final String PFX = "angioclean.";

    /* =================================================================== */
    /*  Entry point                                                        */
    /* =================================================================== */

    @Override
    public void run(String arg) {
        Params p = loadPrefs();
        if (!showDialog(p)) return;
        savePrefs(p);

        boolean haveInput = p.inputDir != null && !p.inputDir.trim().isEmpty();

        if (!haveInput) {
            // Interactive single-image mode: process the active image.
            ImagePlus imp = WindowManager.getCurrentImage();
            if (imp == null) {
                IJ.error("Angio-CLEAN",
                        "No input folder given and no image is open.\n"
                      + "Open an image, or choose an input folder for batch mode.");
                return;
            }
            ImagePlus result = processImage(imp.duplicate(), p);
            result.setTitle(stripExtension(imp.getTitle()) + p.suffix);
            if (p.embedMeta) result.setProperty("Info", provenance(p, imp.getTitle()));
            result.show();
            IJ.showStatus("Angio-CLEAN: processed active image.");
            return;
        }

        // Batch mode.
        File inDir = new File(p.inputDir);
        if (!inDir.isDirectory()) {
            IJ.error("Angio-CLEAN", "Input folder does not exist:\n" + p.inputDir);
            return;
        }
        if (p.outputDir == null || p.outputDir.trim().isEmpty()) {
            IJ.error("Angio-CLEAN", "Please choose an output folder for batch mode.");
            return;
        }
        File outDir = new File(p.outputDir);
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            IJ.error("Angio-CLEAN", "Could not create output folder:\n" + p.outputDir);
            return;
        }
        if (p.radius <= 0 || Double.isNaN(p.radius)) {
            IJ.error("Angio-CLEAN", "Radius/sigma must be a positive number.");
            return;
        }

        List<File> files = collectSorted(inDir, p.recursive);
        if (files.isEmpty()) {
            IJ.error("Angio-CLEAN", "No image files found in:\n" + p.inputDir);
            return;
        }

        // Optional preview of the first image before the whole batch.
        if (showPreviewIfRequested(files.get(0), p) == false) {
            IJ.showStatus("Angio-CLEAN: cancelled at preview.");
            return;
        }

        runBatch(files, inDir, outDir, p);
    }

    /**
     * Public scripting/testing entry point: process every image in a folder.
     * Equivalent to running the plugin in batch mode with the given parameters.
     */
    public void processFolder(File inDir, File outDir, Params p) {
        if (!outDir.isDirectory()) outDir.mkdirs();
        List<File> files = collectSorted(inDir, p.recursive);
        if (!files.isEmpty()) runBatch(files, inDir, outDir, p);
    }

    private List<File> collectSorted(File inDir, boolean recursive) {
        List<File> files = new ArrayList<File>();
        collectImages(inDir, recursive, files);
        Collections.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getAbsolutePath().compareTo(b.getAbsolutePath());
            }
        });
        return files;
    }

    /* =================================================================== */
    /*  Dialog                                                             */
    /* =================================================================== */

    boolean showDialog(Params p) {
        GenericDialog gd = new GenericDialog("Angio-CLEAN  v" + VERSION);

        gd.addMessage("Folders  (leave 'Input' empty to process the active image)");
        gd.addDirectoryField("Input directory", p.inputDir, 38);
        gd.addDirectoryField("Output directory", p.outputDir, 38);

        gd.addMessage("Background estimation");
        gd.addChoice("Background method", METHODS, p.method);
        gd.addNumericField("Radius or sigma", p.radius, 1);
        gd.addCheckbox("Convert to 8-bit first (macro default)", p.to8bit);

        gd.addMessage("Output");
        gd.addChoice("Result type", RESULT_TYPES, p.resultType);
        gd.addCheckbox("Invert result", p.invert);
        gd.addNumericField("Saturated pixels (%) for contrast (0 = off)", p.saturated, 2);
        gd.addChoice("File format", FORMATS, p.format);
        gd.addStringField("Filename suffix", p.suffix, 14);
        gd.addCheckbox("Strip original extension", p.stripExt);

        gd.addMessage("Batch options");
        gd.addCheckbox("Recursive subfolders", p.recursive);
        gd.addCheckbox("Skip existing outputs", p.skipExist);
        gd.addCheckbox("Process stacks slice-by-slice", p.doStacks);
        gd.addNumericField("Threads", p.threads, 0);

        gd.addMessage("Reproducibility");
        gd.addCheckbox("Write CSV report", p.csvReport);
        gd.addCheckbox("Write run log", p.runLog);
        gd.addCheckbox("Embed parameters in image metadata", p.embedMeta);
        gd.addCheckbox("Preview first image before batch", false);

        gd.addHelp("https://github.com/FRJT-BIO/ANGIO-CLEAN");
        gd.showDialog();
        if (gd.wasCanceled()) return false;

        p.inputDir   = gd.getNextString();
        p.outputDir  = gd.getNextString();
        p.method     = gd.getNextChoice();
        p.radius     = gd.getNextNumber();
        p.to8bit     = gd.getNextBoolean();
        p.resultType = gd.getNextChoice();
        p.invert     = gd.getNextBoolean();
        p.saturated  = gd.getNextNumber();
        p.format     = gd.getNextChoice();
        p.suffix     = gd.getNextString();
        p.stripExt   = gd.getNextBoolean();
        p.recursive  = gd.getNextBoolean();
        p.skipExist  = gd.getNextBoolean();
        p.doStacks   = gd.getNextBoolean();
        p.threads    = Math.max(1, (int) Math.round(gd.getNextNumber()));
        p.csvReport  = gd.getNextBoolean();
        p.runLog     = gd.getNextBoolean();
        p.embedMeta  = gd.getNextBoolean();
        this.previewRequested = gd.getNextBoolean();
        return true;
    }

    private boolean previewRequested = false;

    private boolean showPreviewIfRequested(File first, Params p) {
        if (!previewRequested || IJ.getInstance() == null) return true;
        ImagePlus imp = IJ.openImage(first.getAbsolutePath());
        if (imp == null) return true;
        ImagePlus prev = processImage(imp, p);
        prev.setTitle("PREVIEW: " + first.getName());
        prev.show();
        GenericDialog gd = new GenericDialog("Angio-CLEAN preview");
        gd.addMessage("Preview of the first image is shown.\n"
                + "Proceed to process all images?");
        gd.enableYesNoCancel("Process all", "Cancel");
        gd.showDialog();
        prev.changes = false;
        prev.close();
        return gd.wasOKed();
    }

    /* =================================================================== */
    /*  Batch                                                              */
    /* =================================================================== */

    private void runBatch(final List<File> files, final File inDir,
                          final File outDir, final Params p) {

        final long start = System.currentTimeMillis();
        final int total = files.size();
        final AtomicInteger done = new AtomicInteger(0);
        final AtomicInteger okN = new AtomicInteger(0);
        final AtomicInteger skipN = new AtomicInteger(0);
        final AtomicInteger failN = new AtomicInteger(0);

        // Thread-safe collectors for the report (sorted before writing).
        final List<String[]> rows =
                Collections.synchronizedList(new ArrayList<String[]>());
        final List<String> logLines =
                Collections.synchronizedList(new ArrayList<String>());
        final Object logLock = new Object();

        final String ext = formatExtension(p.format);
        final String saveTag = formatSaveTag(p.format);
        final boolean batchModeWas = ij.macro.Interpreter.batchMode;
        ij.macro.Interpreter.batchMode = true;

        IJ.log("==== Angio-CLEAN v" + VERSION + " ====");
        IJ.log(provenance(p, "(batch of " + total + " files)").replace("\n", "  |  "));

        Runnable[] tasks = new Runnable[total];
        for (int i = 0; i < total; i++) {
            final File f = files.get(i);
            tasks[i] = new Runnable() {
                public void run() {
                    long t0 = System.currentTimeMillis();
                    File targetDir = outDir;
                    if (p.recursive) {
                        String rel = relativeParent(inDir, f);
                        if (!rel.isEmpty()) {
                            targetDir = new File(outDir, rel);
                            targetDir.mkdirs();
                        }
                    }
                    String base = p.stripExt ? stripExtension(f.getName()) : f.getName();
                    File outFile = new File(targetDir, base + p.suffix + ext);

                    if (p.skipExist && outFile.exists()) {
                        skipN.incrementAndGet();
                        rows.add(new String[]{f.getName(), "skipped", "", "", "", "", "", "", ""});
                        log(logLines, logLock, "SKIP  " + f.getName());
                        progress(done, total);
                        return;
                    }
                    try {
                        ImagePlus imp = IJ.openImage(f.getAbsolutePath());
                        if (imp == null) {
                            failN.incrementAndGet();
                            rows.add(new String[]{f.getName(), "fail:open", "", "", "", "", "", "", ""});
                            log(logLines, logLock, "FAIL  " + f.getName() + " (could not open)");
                            progress(done, total);
                            return;
                        }
                        String inType = typeName(imp);
                        ImagePlus result = processImage(imp, p);
                        if (p.embedMeta) result.setProperty("Info", provenance(p, f.getName()));
                        IJ.saveAs(result, saveTag, outFile.getAbsolutePath());

                        ImageStatistics st = result.getProcessor().getStatistics();
                        long dt = System.currentTimeMillis() - t0;
                        rows.add(new String[]{
                                f.getName(), "ok",
                                String.valueOf(result.getWidth()),
                                String.valueOf(result.getHeight()),
                                inType, typeName(result),
                                fmt(st.min), fmt(st.max), String.valueOf(dt)});
                        imp.close();
                        result.close();
                        okN.incrementAndGet();
                        log(logLines, logLock, "OK    " + f.getName() + " -> " + outFile.getName()
                                + "  (" + dt + " ms)");
                    } catch (Throwable t) {
                        failN.incrementAndGet();
                        rows.add(new String[]{f.getName(), "fail:" + t.getClass().getSimpleName(),
                                "", "", "", "", "", "", ""});
                        log(logLines, logLock, "FAIL  " + f.getName() + " (" + t.getMessage() + ")");
                    }
                    progress(done, total);
                }
            };
        }

        try {
            if (p.threads <= 1) {
                for (Runnable r : tasks) r.run();
            } else {
                ExecutorService ex = Executors.newFixedThreadPool(p.threads);
                for (Runnable r : tasks) ex.execute(r);
                ex.shutdown();
                try { ex.awaitTermination(7, TimeUnit.DAYS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        } finally {
            ij.macro.Interpreter.batchMode = batchModeWas;
            IJ.showProgress(1.0);
        }

        double secs = (System.currentTimeMillis() - start) / 1000.0;
        String summary = String.format(Locale.US,
                "Done. %d processed, %d skipped, %d failed  (%.1f s, %d thread(s))",
                okN.get(), skipN.get(), failN.get(), secs, p.threads);
        IJ.log("---------------------------");
        IJ.log(summary);
        IJ.showStatus("Angio-CLEAN: " + summary);

        // Sort rows by filename for a deterministic report.
        Collections.sort(rows, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) { return a[0].compareTo(b[0]); }
        });
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        if (p.csvReport) writeCsv(new File(outDir, "AngioCLEAN_report_" + stamp + ".csv"), rows);
        if (p.runLog)   writeLog(new File(outDir, "AngioCLEAN_run_" + stamp + ".log"),
                                 p, inDir, outDir, logLines, summary);
    }

    private static void progress(AtomicInteger done, int total) {
        IJ.showProgress(done.incrementAndGet(), total);
    }

    private static void log(List<String> sink, Object lock, String line) {
        sink.add(line);
        synchronized (lock) { IJ.log(line); }
    }

    /* =================================================================== */
    /*  Core algorithm                                                     */
    /* =================================================================== */

    /**
     * Full-featured processing of one image (handles stacks).
     * @param imp  source image (consumed)
     * @param p    parameters
     * @return processed image (new)
     */
    public static ImagePlus processImage(ImagePlus imp, Params p) {
        int n = imp.getStackSize();
        if (n > 1 && p.doStacks) {
            ImageStack out = null;
            for (int s = 1; s <= n; s++) {
                ImagePlus slice = new ImagePlus("s", imp.getStack().getProcessor(s).duplicate());
                ImagePlus r = processSingle(slice, p);
                if (out == null) out = new ImageStack(r.getWidth(), r.getHeight());
                out.addSlice(imp.getStack().getSliceLabel(s), r.getProcessor());
            }
            ImagePlus result = new ImagePlus(imp.getTitle(), out);
            result.setCalibration(imp.getCalibration());
            return result;
        }
        return processSingle(imp, p);
    }

    /** Backward-compatible simple API (used by tests and old scripts). */
    public static ImagePlus processImage(ImagePlus imp, double sigma, String mode) {
        Params p = new Params();
        p.method = M_GAUSSIAN;
        p.radius = sigma;
        p.to8bit = true;
        if (mode.startsWith("RGB"))       p.resultType = R_RGB;
        else if (mode.startsWith("8"))    p.resultType = R_8;
        else                              p.resultType = R_32;
        return processSingle(imp, p);
    }

    /** Single 2-D image core. With defaults this equals the original macro. */
    private static ImagePlus processSingle(ImagePlus imp, Params p) {
        // 1) optional 8-bit
        if (p.to8bit && imp.getType() != ImagePlus.GRAY8 && imp.getType() != ImagePlus.COLOR_RGB)
            new ImageConverter(imp).convertToGray8();
        else if (p.to8bit && imp.getType() == ImagePlus.COLOR_RGB)
            new ImageConverter(imp).convertToGray8();

        // 2) estimate background on a copy
        ImagePlus bg = imp.duplicate();
        ImageProcessor bgp = bg.getProcessor();
        if (M_GAUSSIAN.equals(p.method)) {
            new GaussianBlur().blurGaussian(bgp, p.radius, p.radius, 0.002);
        } else if (M_ROLLING.equals(p.method)) {
            new BackgroundSubtracter().rollingBallBackground(
                    bgp, p.radius, /*createBackground*/ true,
                    /*lightBackground*/ false, /*useParaboloid*/ false,
                    /*doPresmooth*/ true, /*correctCorners*/ false);
        } else { // median
            new RankFilters().rank(bgp, p.radius, RankFilters.MEDIAN);
        }

        // 3) subtract: image - background  (32-bit float)
        ImagePlus result = new ImageCalculator()
                .run("Subtract create 32-bit", imp, bg);
        bg.close();

        // 4) optional invert
        if (p.invert) result.getProcessor().invert();

        // 5) output type
        if (R_RGB.equals(p.resultType)) {
            new ImageConverter(result).convertToRGB();
        } else if (R_8.equals(p.resultType)) {
            new ImageConverter(result).convertToGray8();
        } else if (R_SAME.equals(p.resultType)) {
            convertToType(result, imp.getType());
        } // R_32 -> leave float

        // 6) optional contrast stretch
        if (p.saturated > 0) new ContrastEnhancer().stretchHistogram(result, p.saturated);

        result.setTitle("Result");
        return result;
    }

    private static void convertToType(ImagePlus imp, int targetType) {
        ImageConverter ic = new ImageConverter(imp);
        switch (targetType) {
            case ImagePlus.GRAY8:    ic.convertToGray8();  break;
            case ImagePlus.GRAY16:   ic.convertToGray16(); break;
            case ImagePlus.COLOR_RGB:ic.convertToRGB();    break;
            default: /* GRAY32 stays */ break;
        }
    }

    /* =================================================================== */
    /*  Provenance / reports                                               */
    /* =================================================================== */

    static String provenance(Params p, String source) {
        StringBuilder sb = new StringBuilder();
        sb.append("Angio-CLEAN v").append(VERSION).append('\n');
        sb.append("date=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append('\n');
        sb.append("source=").append(source).append('\n');
        sb.append("method=").append(p.method).append('\n');
        sb.append("radius_or_sigma=").append(p.radius).append('\n');
        sb.append("convert_to_8bit=").append(p.to8bit).append('\n');
        sb.append("invert=").append(p.invert).append('\n');
        sb.append("result_type=").append(p.resultType).append('\n');
        sb.append("saturated_percent=").append(p.saturated).append('\n');
        sb.append("imagej=").append(IJ.getFullVersion()).append('\n');
        sb.append("java=").append(System.getProperty("java.version"));
        return sb.toString();
    }

    private void writeCsv(File file, List<String[]> rows) {
        try {
            FileWriter w = new FileWriter(file);
            w.write("filename,status,width,height,input_type,output_type,min,max,time_ms\n");
            for (String[] r : rows) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < r.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(csvCell(r[i]));
                }
                w.write(sb.append('\n').toString());
            }
            w.close();
            IJ.log("CSV report: " + file.getAbsolutePath());
        } catch (IOException e) {
            IJ.log("Could not write CSV: " + e.getMessage());
        }
    }

    private void writeLog(File file, Params p, File inDir, File outDir,
                          List<String> lines, String summary) {
        try {
            FileWriter w = new FileWriter(file);
            w.write("Angio-CLEAN v" + VERSION + " run log\n");
            w.write(provenance(p, "(batch)") + "\n");
            w.write("input_dir=" + inDir.getAbsolutePath() + "\n");
            w.write("output_dir=" + outDir.getAbsolutePath() + "\n");
            w.write("threads=" + p.threads + "\n");
            w.write("format=" + p.format + "  suffix=" + p.suffix + "\n");
            w.write("----------------------------------------\n");
            List<String> snapshot = new ArrayList<String>(lines);
            Collections.sort(snapshot);
            for (String l : snapshot) w.write(l + "\n");
            w.write("----------------------------------------\n");
            w.write(summary + "\n");
            w.close();
            IJ.log("Run log: " + file.getAbsolutePath());
        } catch (IOException e) {
            IJ.log("Could not write run log: " + e.getMessage());
        }
    }

    private static String csvCell(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    /* =================================================================== */
    /*  Helpers                                                            */
    /* =================================================================== */

    private void collectImages(File dir, boolean recursive, List<File> out) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        Arrays.sort(entries);
        for (File e : entries) {
            if (e.isDirectory()) {
                if (recursive) collectImages(e, true, out);
            } else if (!e.isHidden() && isImage(e.getName())) {
                out.add(e);
            }
        }
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.US);
        for (String ext : IMAGE_EXT) if (lower.endsWith(ext)) return true;
        return false;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    private static String formatExtension(String format) {
        if ("PNG".equals(format))  return ".png";
        if ("JPEG".equals(format)) return ".jpg";
        return ".tif";
    }

    private static String formatSaveTag(String format) {
        if ("PNG".equals(format))  return "PNG";
        if ("JPEG".equals(format)) return "Jpeg";
        return "Tiff";
    }

    private static String typeName(ImagePlus imp) {
        switch (imp.getType()) {
            case ImagePlus.GRAY8:     return "8-bit";
            case ImagePlus.GRAY16:    return "16-bit";
            case ImagePlus.GRAY32:    return "32-bit";
            case ImagePlus.COLOR_RGB: return "RGB";
            case ImagePlus.COLOR_256: return "8-bit-color";
            default:                  return "?";
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.4g", v);
    }

    private static String relativeParent(File root, File file) {
        String rootPath = root.getAbsolutePath();
        String parent = file.getParentFile().getAbsolutePath();
        if (parent.length() > rootPath.length() && parent.startsWith(rootPath)) {
            String rel = parent.substring(rootPath.length());
            if (rel.startsWith(File.separator)) rel = rel.substring(1);
            return rel;
        }
        return "";
    }

    /* --------------------------- preferences -------------------------- */

    private Params loadPrefs() {
        Params p = new Params();
        p.inputDir   = Prefs.get(PFX + "input", p.inputDir);
        p.outputDir  = Prefs.get(PFX + "output", p.outputDir);
        p.method     = Prefs.get(PFX + "method", p.method);
        p.radius     = Prefs.get(PFX + "radius", p.radius);
        p.to8bit     = Prefs.get(PFX + "to8bit", p.to8bit);
        p.invert     = Prefs.get(PFX + "invert", p.invert);
        p.resultType = Prefs.get(PFX + "result", p.resultType);
        p.saturated  = Prefs.get(PFX + "saturated", p.saturated);
        p.format     = Prefs.get(PFX + "format", p.format);
        p.suffix     = Prefs.get(PFX + "suffix", p.suffix);
        p.stripExt   = Prefs.get(PFX + "strip", p.stripExt);
        p.recursive  = Prefs.get(PFX + "recursive", p.recursive);
        p.skipExist  = Prefs.get(PFX + "skip", p.skipExist);
        p.doStacks   = Prefs.get(PFX + "stacks", p.doStacks);
        p.threads    = (int) Prefs.get(PFX + "threads", p.threads);
        p.csvReport  = Prefs.get(PFX + "csv", p.csvReport);
        p.runLog     = Prefs.get(PFX + "runlog", p.runLog);
        p.embedMeta  = Prefs.get(PFX + "embed", p.embedMeta);
        return p;
    }

    private void savePrefs(Params p) {
        Prefs.set(PFX + "input", p.inputDir);
        Prefs.set(PFX + "output", p.outputDir);
        Prefs.set(PFX + "method", p.method);
        Prefs.set(PFX + "radius", p.radius);
        Prefs.set(PFX + "to8bit", p.to8bit);
        Prefs.set(PFX + "invert", p.invert);
        Prefs.set(PFX + "result", p.resultType);
        Prefs.set(PFX + "saturated", p.saturated);
        Prefs.set(PFX + "format", p.format);
        Prefs.set(PFX + "suffix", p.suffix);
        Prefs.set(PFX + "strip", p.stripExt);
        Prefs.set(PFX + "recursive", p.recursive);
        Prefs.set(PFX + "skip", p.skipExist);
        Prefs.set(PFX + "stacks", p.doStacks);
        Prefs.set(PFX + "threads", p.threads);
        Prefs.set(PFX + "csv", p.csvReport);
        Prefs.set(PFX + "runlog", p.runLog);
        Prefs.set(PFX + "embed", p.embedMeta);
    }
}
