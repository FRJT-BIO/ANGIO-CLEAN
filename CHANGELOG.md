# Changelog

All notable changes to Angio-CLEAN are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [3.0.0] - 2026-06-27
### Added
- Three background-estimation methods: Gaussian blur (default, identical to the
  original macro), rolling ball, and median.
- Result-type choice: RGB, 32-bit (raw), 8-bit, or same as input.
- Optional result inversion and contrast stretch (% saturated pixels).
- Stack support (each slice processed independently).
- 16-bit / non-8-bit input handling (the 8-bit conversion is now optional).
- Multi-threaded batch processing (results are independent of thread count).
- **Reproducibility:** per-image embedded provenance metadata (parameters,
  software versions) plus a CSV report and a human-readable run log.
- Optional preview of the first image before committing to the batch.
- Public `processImage(ImagePlus, Params)` and `processFolder(...)` API for
  scripting.
- Self-contained `build.sh` and GitHub Actions CI with a 16-test headless suite.

### Changed
- Default output filename strips the original extension (configurable).

### Guaranteed
- With default settings the output is **pixel-identical** to the original
  `Angio-CLEAN.ijm` macro (verified by an automated test).
- Built targeting **Java 8** bytecode (class file version 52) for compatibility
  with ImageJ/Fiji installations that bundle Java 8.

## [2.0.0]
### Added
- First Java plugin version: single dialog with input/output folders,
  adjustable sigma, format/mode choices, recursive option, progress bar,
  summary log, and per-file error handling.

## [1.0.0]
- Original `Angio-CLEAN.ijm` ImageJ macro: 8-bit → Gaussian blur (σ=30) →
  subtract (32-bit) → RGB → save as TIFF.
