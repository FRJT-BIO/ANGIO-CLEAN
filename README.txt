========================================================================
  ANGIO-CLEAN
========================================================================

A free, open-source ImageJ/Fiji plug-in that removes the halo artifact
from angiogenesis assay images to improve automated quantification.

Angio-CLEAN flattens the uneven background of in vitro angiogenesis
images so that automated tools (such as the Angiogenesis Analyzer for
ImageJ) can quantify the vascular network more accurately. It is the
maintained successor of an earlier Angio-CLEAN.ijm macro; with default
settings its output is pixel-identical to that macro.

License : GNU General Public License v3.0 (see the LICENSE file)
Build   : status shown on the "Actions" tab of the GitHub repository


------------------------------------------------------------------------
  QUICK INSTALL  (pre-built plugin)
------------------------------------------------------------------------

  1. Download  Angio-CLEAN_.jar  from the repository's "Releases" page
     (or from the  download_plugin/  folder).
  2. Copy it into your  ImageJ/plugins   (or  Fiji.app/plugins)  folder.
  3. Restart ImageJ/Fiji.
  4. Run it from:   Plugins > Angio-CLEAN...

  No compilation or dependencies required.

  Compatibility: the plug-in is built for Java 8, so it runs on
  ImageJ/Fiji installations bundling Java 8 or any newer Java.


------------------------------------------------------------------------
  USAGE
------------------------------------------------------------------------

  Open  Plugins > Angio-CLEAN...  and set:

  Input directory    Folder of images to process. Leave empty to
                     process the image currently open instead.
  Output directory   Where processed images are written (batch mode).
  Background method   Gaussian blur (default), Rolling ball, or Median.
  Radius or sigma    sigma for Gaussian, radius for the others
                     (default 30).
  Convert to 8-bit   On = original macro behaviour. Off = keep native
                     bit depth (e.g. 16-bit).
  Result type        RGB (macro default), 32-bit (raw subtract),
                     8-bit, or Same as input.
  Invert result      Flip intensities (for dark-on-light vessels).
  Saturated (%)      Optional contrast stretch (0 = off).
  File format        TIFF / PNG / JPEG.
  Filename suffix    Appended to each output name (default _processed).
  Recursive          Process sub-folders, mirroring the structure.
  Skip existing      Do not reprocess files already produced.
  Process stacks     Process each slice of multi-page images.
  Threads            Parallel workers. Output is identical regardless
                     of this value.
  CSV / log / meta   Reproducibility outputs (see below).
  Preview first      Show the first result and confirm before the batch.

  Supported inputs:  .tif .tiff .jpg .jpeg .png .bmp .gif
  All settings are remembered between sessions.

  Algorithm (for each image or slice):
    1. (optional) convert to 8-bit;
    2. estimate the background (Gaussian / rolling ball / median);
    3. result = image - background, in 32-bit float;
    4. (optional) invert, convert to the chosen output type, stretch;
    5. save.
  With defaults (Gaussian, sigma=30, 8-bit, RGB, TIFF) this reproduces
  Angio-CLEAN.ijm exactly.

  Reproducibility outputs (written in the output folder):
    - AngioCLEAN_report_<timestamp>.csv : per-image status, size,
      type, min/max, time.
    - AngioCLEAN_run_<timestamp>.log : full parameter set, software
      versions, per-file log.
    - The exact parameters are also embedded in each output image's
      metadata (Image > Show Info... in ImageJ).


------------------------------------------------------------------------
  BUILD FROM SOURCE
------------------------------------------------------------------------

  Option A - Maven (standard ImageJ ecosystem):
      mvn clean package
      -> target/Angio-CLEAN_-3.0.0.jar

  Option B - No Maven (self-contained script):
      ./build.sh
      -> Angio-CLEAN_.jar

  build.sh fetches the dependency-free ImageJ1 source, compiles it into
  a local ij.jar, then builds the plugin (targeting Java 8).

  Tests:
      ./build.sh
      javac -cp ij.jar:Angio-CLEAN_.jar -d .test \
            src/test/java/TestAngioV3.java
      xvfb-run -a java -cp ij.jar:Angio-CLEAN_.jar:.test TestAngioV3


------------------------------------------------------------------------
  HOW TO CITE
------------------------------------------------------------------------

  Angio-CLEAN is free to use under the GPL-3.0 license. If you use it
  in work that leads to a publication, please also cite the original
  article describing the plug-in:

      Jimenez-Trinidad FR, Sardine A, et al. Angio-CLEAN: a
      user-friendly ImageJ plug-in for halo-artifact removal that
      improves automated angiogenesis quantification.
      DOI: to be added upon publication.

  A CITATION.cff file is included, so GitHub shows a "Cite this
  repository" button in the sidebar.


------------------------------------------------------------------------
  LICENSE
------------------------------------------------------------------------

  GNU General Public License v3.0 (see the LICENSE file).
  (c) 2024-2026 Francisco Rafael Jimenez-Trinidad and contributors.


------------------------------------------------------------------------
  ACKNOWLEDGEMENTS
------------------------------------------------------------------------

  Built on ImageJ (Wayne Rasband, NIH). Originally derived from the
  Angio-CLEAN.ijm macro.
