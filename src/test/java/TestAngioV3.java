import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.process.*;
import java.io.*;
import java.util.*;

public class TestAngioV3 {
    static final String TMP = System.getProperty("java.io.tmpdir");
    static int pass=0, fail=0;
    static void check(String n, boolean c){ if(c){pass++;System.out.println("[PASS] "+n);} else {fail++;System.out.println("[FAIL] "+n);} }

    static ByteProcessor synth(int w,int h){
        ByteProcessor bp=new ByteProcessor(w,h);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)bp.set(x,y,60+(int)(120.0*x/w));
        bp.setValue(245); bp.setLineWidth(6);
        bp.drawLine(20,40,380,90); bp.drawLine(50,250,350,60);
        bp.drawLine(200,20,180,280); bp.drawOval(120,110,90,90);
        return bp;
    }

    public static void main(String[] a) throws Exception {
        File in=new File(TMP+"/v3_in"), out=new File(TMP+"/v3_out");
        deleteDir(in); deleteDir(out); in.mkdirs(); out.mkdirs();

        // 8-bit png + tif
        new FileSaver(new ImagePlus("a",synth(400,300))).saveAsPng(new File(in,"vessel_a.png").getAbsolutePath());
        new FileSaver(new ImagePlus("b",synth(400,300))).saveAsTiff(new File(in,"vessel_b.tif").getAbsolutePath());
        // 16-bit tif
        ShortProcessor sp=new ShortProcessor(400,300);
        ByteProcessor base=synth(400,300);
        for(int i=0;i<400*300;i++) sp.set(i, base.get(i)*200);
        new FileSaver(new ImagePlus("c16",sp)).saveAsTiff(new File(in,"vessel_c16.tif").getAbsolutePath());
        // a stack (3 slices)
        ImageStack st=new ImageStack(400,300);
        st.addSlice(synth(400,300)); st.addSlice(synth(400,300)); st.addSlice(synth(400,300));
        new FileSaver(new ImagePlus("stk",st)).saveAsTiff(new File(in,"vessel_stack.tif").getAbsolutePath());

        Angio_CLEAN plugin = new Angio_CLEAN();

        // ---- Test 1: default params == macro behaviour (Gaussian, 8bit, RGB) ----
        Angio_CLEAN.Params def = new Angio_CLEAN.Params();
        def.threads=1; def.format="TIFF";
        plugin.processFolder(in, out, def);
        File rgbA=new File(out,"vessel_a_processed.tif");
        check("default run produced output", rgbA.exists());
        ImagePlus rA=IJ.openImage(rgbA.getAbsolutePath());
        check("default output is RGB", rA.getType()==ImagePlus.COLOR_RGB);

        // Compare default Gaussian result to a hand-computed macro reference
        ImagePlus ref = macroReference(synth(400,300), 30);
        ImagePlus got = Angio_CLEAN.processImage(new ImagePlus("x",synth(400,300)),30,"RGB (like original)");
        check("default == macro reference (pixel-identical)", samePixels(ref,got));

        // ---- Test 2: CSV + log written ----
        File[] csvs = out.listFiles((d,n)->n.startsWith("AngioCLEAN_report_")&&n.endsWith(".csv"));
        File[] logs = out.listFiles((d,n)->n.startsWith("AngioCLEAN_run_")&&n.endsWith(".log"));
        check("CSV report written", csvs!=null && csvs.length>=1);
        check("run log written", logs!=null && logs.length>=1);
        if(csvs!=null&&csvs.length>0){
            String csv=readAll(csvs[0]);
            check("CSV has header", csv.startsWith("filename,status,width,height"));
            check("CSV lists vessel_a", csv.contains("vessel_a.png"));
        }

        // ---- Test 3: embedded provenance metadata in TIFF ----
        String info = (String) rA.getProperty("Info");
        check("metadata embedded in output", info!=null && info.contains("Angio-CLEAN v"));
        check("metadata records method", info!=null && info.contains("method=Gaussian blur"));
        check("metadata records sigma", info!=null && info.contains("radius_or_sigma=30"));

        // ---- Test 4: stack processed slice-by-slice ----
        ImagePlus rStk=IJ.openImage(new File(out,"vessel_stack_processed.tif").getAbsolutePath());
        check("stack output has 3 slices", rStk!=null && rStk.getStackSize()==3);

        // ---- Test 5: 16-bit handled (with to8bit=false, keep depth via Same) ----
        deleteDir(out); out.mkdirs();
        Angio_CLEAN.Params keep = new Angio_CLEAN.Params();
        keep.threads=2; keep.to8bit=false; keep.resultType="32-bit (raw subtract)"; keep.format="TIFF";
        plugin.processFolder(in, out, keep);
        ImagePlus r16=IJ.openImage(new File(out,"vessel_c16_processed.tif").getAbsolutePath());
        check("16-bit input -> 32-bit raw output", r16!=null && r16.getType()==ImagePlus.GRAY32);

        // ---- Test 6: rolling ball and median methods run ----
        for(String m : new String[]{"Rolling ball","Median"}){
            Angio_CLEAN.Params mp=new Angio_CLEAN.Params();
            mp.method=m; mp.radius=20; mp.threads=2; mp.resultType="8-bit";
            ImagePlus rr=Angio_CLEAN.processImage(new ImagePlus("m",synth(400,300)),
                    mkParams(m,20));
            check("method '"+m+"' produces 8-bit", rr.getType()==ImagePlus.GRAY8);
        }

        // ---- Test 7: multithread == singlethread (determinism) ----
        deleteDir(out); out.mkdirs();
        Angio_CLEAN.Params t1=new Angio_CLEAN.Params(); t1.threads=1; t1.format="TIFF";
        plugin.processFolder(in, out, t1);
        byte[] s1 = readBytes(new File(out,"vessel_a_processed.tif"));
        deleteDir(out); out.mkdirs();
        Angio_CLEAN.Params t4=new Angio_CLEAN.Params(); t4.threads=4; t4.format="TIFF";
        plugin.processFolder(in, out, t4);
        ImagePlus a1=open(s1); ImagePlus a4=IJ.openImage(new File(out,"vessel_a_processed.tif").getAbsolutePath());
        check("1-thread and 4-thread outputs identical", samePixels(a1,a4));

        // ---- Test 8: invert flips ----
        ImagePlus noInv=Angio_CLEAN.processImage(new ImagePlus("n",synth(400,300)), mkParams("Gaussian blur",30,false,"8-bit"));
        ImagePlus inv  =Angio_CLEAN.processImage(new ImagePlus("i",synth(400,300)), mkParams("Gaussian blur",30,true,"8-bit"));
        check("invert changes pixels", noInv.getProcessor().get(200,65)!=inv.getProcessor().get(200,65));

        System.out.println("\nRESULT passed="+pass+" failed="+fail);
        if(fail>0) System.exit(1);
        System.out.println("ALL V3 TESTS PASSED");
    }

    // hand-computed reference of the original macro pipeline
    static ImagePlus macroReference(ByteProcessor bp, double sigma){
        ImagePlus imp=new ImagePlus("ref",bp);
        new ij.process.ImageConverter(imp).convertToGray8();
        ImagePlus blur=imp.duplicate();
        new ij.plugin.filter.GaussianBlur().blurGaussian(blur.getProcessor(),sigma,sigma,0.002);
        ImagePlus res=new ij.plugin.ImageCalculator().run("Subtract create 32-bit",imp,blur);
        new ij.process.ImageConverter(res).convertToRGB();
        return res;
    }
    static Angio_CLEAN.Params mkParams(String method,double r){ return mkParams(method,r,false,"8-bit"); }
    static Angio_CLEAN.Params mkParams(String method,double r,boolean inv,String rt){
        Angio_CLEAN.Params p=new Angio_CLEAN.Params(); p.method=method; p.radius=r; p.invert=inv; p.resultType=rt; return p;
    }
    static boolean samePixels(ImagePlus a, ImagePlus b){
        if(a==null||b==null) return false;
        if(a.getWidth()!=b.getWidth()||a.getHeight()!=b.getHeight()) return false;
        int[] pa=(int[])a.getProcessor().convertToRGB().getPixels();
        int[] pb=(int[])b.getProcessor().convertToRGB().getPixels();
        return Arrays.equals(pa,pb);
    }
    static ImagePlus open(byte[] tif) throws Exception {
        File t=File.createTempFile("ang",".tif"); FileOutputStream fo=new FileOutputStream(t); fo.write(tif); fo.close();
        return IJ.openImage(t.getAbsolutePath());
    }
    static byte[] readBytes(File f) throws Exception { java.nio.file.Files.readAllBytes(f.toPath()); return java.nio.file.Files.readAllBytes(f.toPath()); }
    static String readAll(File f) throws Exception { return new String(java.nio.file.Files.readAllBytes(f.toPath())); }
    static void deleteDir(File d){ if(d.exists()){ File[] fs=d.listFiles(); if(fs!=null)for(File f:fs){ if(f.isDirectory())deleteDir(f); else f.delete(); } d.delete(); } }
}
