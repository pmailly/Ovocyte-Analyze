package mcib_testing;

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.Duplicator;
import ij.plugin.filter.DifferenceOfGaussians;
import ij.plugin.filter.GaussianBlur;
import ij.plugin.filter.ParticleAnalyzer;
import ij.plugin.filter.RankFilters;
import ij.process.AutoThresholder;
import ij.process.ImageProcessor;
import ij.process.StackStatistics;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import mcib3d.geom.Object3D;
import mcib3d.geom.Objects3DPopulation;
import mcib3d.geom.Voxel3D;
import mcib3d.image3d.ImageByte;
import mcib3d.image3d.ImageFloat;
import mcib3d.image3d.ImageHandler;
import mcib3d.image3d.ImageInt;
import mcib3d.image3d.ImageLabeller;
import mcib3d.image3d.distanceMap3d.EDT;

/**
 *
 * @author thomasb and philippem
 */
public class Ovocyte_Mtocs implements ij.plugin.PlugIn {

    //private Object3D spindle = null;
    //private Objects3DPopulation spots = null;
    private final boolean canceled = false;
    private int nbChannel = 0;
    private Calibration cal = new Calibration();
    ImageHandler img;

// Find channel name in ND file
    public String[] Find_nd_info(String NDdir, String NDfile) throws FileNotFoundException, IOException {
        BufferedReader br = new BufferedReader(new FileReader(NDdir + NDfile));
        int nbline = 0;
        String strLine;
        String[] line;
        nbChannel = 0;
        String[] waveName = {"", "", ""};
        int nbWave = 1;
        while ((strLine = br.readLine()) != null) {
            if (strLine.startsWith("\"NWavelengths\"")) {    // find channel number
                line = strLine.split(", ");
                nbChannel = Integer.valueOf(line[1]);
            }

            if (strLine.startsWith("\"WaveName" + nbWave + "\"")) {
                line = strLine.split("\"");
                waveName[nbWave - 1] = "_w" + nbWave + line[3];
                nbWave++;
            }
        }
        return (waveName);
    }

// find ovocyte boundaries 
    public Roi find_crop(ImagePlus BG) {
        ImageProcessor ip = BG.getProcessor();
        RankFilters var = new RankFilters();
        ResultsTable rt = new ResultsTable();

        ParticleAnalyzer measure = new ParticleAnalyzer(ParticleAnalyzer.EXCLUDE_EDGE_PARTICLES + ParticleAnalyzer.INCLUDE_HOLES,
                ParticleAnalyzer.RECT + ParticleAnalyzer.AREA, rt, 200000, 6500000);

        var.rank(ip, 25, RankFilters.VARIANCE);
        ip.setAutoThreshold(AutoThresholder.Method.Triangle, true);
        measure.analyze(BG, ip);
        return (new Roi((int) rt.getValue("BX", 0), (int) rt.getValue("BY", 0),
                (int) rt.getValue("Width", 0), (int) rt.getValue("Height", 0)));
    }

// crop GFP or RFP to keep only ovocyte boundaries
    public ImagePlus crop_image(ImagePlus img, Roi roicrop) {
        ImageProcessor ipGfp = img.getProcessor();
        img.setRoi(roicrop);
        ipGfp.crop();
        img.updateAndDraw();
        ImagePlus imgcrop = new Duplicator().run(img);
        return (imgcrop);
    }

// Gaussian filter GFP image 
    public ImagePlus gfp_filter(ImagePlus img) {
        GaussianBlur gaussian = new GaussianBlur();
        ImageStack stack = img.getStack();
        for (int s = 1; s <= img.getNSlices(); s++) {
            gaussian.blurGaussian(stack.getProcessor(s), 4, 4, 0.02);
        }
        img.updateAndDraw();
        img.setTitle("GFP_filtered");
        return (img);
    }

// Differences of  Gaussian filter RFP image 
    public ImagePlus rfp_filter(ImagePlus img) {
        GaussianBlur gauss = new GaussianBlur();
        for (int s = 0; s <= img.getNSlices(); s++) {
            img.setSlice(s);
            gauss.blurGaussian(img.getProcessor(), 2, 2, 0.02);
            // JE NE TROUVE PAS CE PLUGIN, QUELLE VERSION DE IJ ?
            DifferenceOfGaussians.run(img.getProcessor(), 20, 1);
        }
        img.updateAndDraw();
        img.setTitle("RFP_filtered");
        return (img);
    }

// Threshold images
    public ImagePlus threshold(ImagePlus img, boolean gfp) {
        AutoThresholder at = new AutoThresholder();
        int th;
        StackStatistics stats = new StackStatistics(img);
        if (gfp) {
            th = at.getThreshold(AutoThresholder.Method.Yen, stats.histogram16);
        } else {
            th = at.getThreshold(AutoThresholder.Method.Default, stats.histogram16);
        }
        ImageHandler ha = ImageHandler.wrap(img);
        ImageByte bin = ha.thresholdAboveExclusive(th);
        bin.setCalibration(cal);
        return bin.getImagePlus();
    }

    @Override
    public void run(String arg) {

        cal.pixelWidth = 0.1613;
        cal.pixelHeight = 0.1613;
        cal.pixelDepth = 1;
        cal.setUnit("micron");

        try {
            if (canceled) {
                IJ.showMessage(" Pluging canceled");
                return;
            }
            String imageDir = IJ.getDirectory("Choose Directory Containing ND Files...");
            if (imageDir == null) {
                return;
            }
            File inDir = new File(imageDir);
            String[] imageFile = inDir.list();
            if (imageFile == null) {
                return;
            }
            String[] channelName = null;
            // Write headers results for distance file 
            FileWriter fwDistAnalyze;
            fwDistAnalyze = new FileWriter(imageDir + "Analyze_MTOCS_results.xls", false);
            BufferedWriter outputDistAnalyze = new BufferedWriter(fwDistAnalyze);
            outputDistAnalyze.write("Image\tSpindle Vol\tSpindle Feret\tMtoc Volume\tMtoc Distance to pole\tMtoc Distance to border\t"
                    + "Mtoc Distance to center\tEVF Spindle\tEVF Mtocs\n");
            outputDistAnalyze.flush();

            // 
            for (int f = 0; f < imageFile.length; f++) {
                if (imageFile[f].endsWith(".nd")) {
                    channelName = Find_nd_info(imageDir, imageFile[f]);
                    String imageName = imageFile[f].substring(0, imageFile[f].indexOf(".nd"));
                    Opener opener = new Opener();
                    ImagePlus brightfield = new ImagePlus();

                    // open brighField image and find Roi to crop images
                    Roi cropRoi;
                    brightfield = opener.openTiff(imageDir, imageName + channelName[0] + ".TIF");
                    cropRoi = find_crop(brightfield);
                    brightfield.close();
                    brightfield.flush();

                    // open GFP channel
                    ImagePlus gfp = new ImagePlus();
                    ImagePlus gfp_crop = new ImagePlus();

                    gfp = opener.openTiff(imageDir, imageName + channelName[1] + ".TIF");

                    IJ.showStatus("Processing image " + imageName);
                    // crop gfp image to Ovocyte size
                    gfp_crop = crop_image(gfp, cropRoi);
                    gfp.close();
                    gfp.flush();
                    // filter spindle (GFP)
                    gfp_crop = gfp_filter(gfp_crop);
                    gfp_crop = threshold(gfp_crop, true);
                    gfp_crop.setCalibration(cal);
                    ImageProcessor ipGfp = gfp_crop.getProcessor();
                    for (int s = 1; s <= gfp_crop.getNSlices(); s++) {
                        gfp_crop.setSlice(s);
                        for (int n = 0; n < 4; n++) {
                            ipGfp.erode();
                        }
                    }
                    gfp_crop.updateAndDraw();
                    FileSaver gfpSave = new FileSaver(gfp_crop);
                    gfpSave.saveAsTiffStack(imageDir + imageName + channelName[1] + "_mask.tif");

                    //open RFP channel (MTOCS)
                    ImagePlus rfp = new ImagePlus();
                    ImagePlus rfp_crop = new ImagePlus();
                    rfp = opener.openTiff(imageDir, imageName + channelName[2] + ".TIF");
                    // crop gfp image to Ovocyte size
                    rfp_crop = crop_image(rfp, cropRoi);
                    rfp.close();
                    rfp.flush();

                    // filter spindle (RFP)
                    rfp_crop = rfp_filter(rfp_crop);
                    rfp_crop = threshold(rfp_crop, false);
                    rfp_crop.setCalibration(cal);
                    FileSaver rfpSave = new FileSaver(rfp_crop);
                    rfpSave.saveAsTiffStack(imageDir + imageName + channelName[2] + "_mask.tif");

                    // ASSUME WE HAVE BINARY IMAGES HERE
                    //gfp_crop.show("spindle");
                    //rfp_crop.show("spots");
                    //new WaitForUserDialog("wait").show();
                    Objects3DPopulation popTmp = getPopFromImage(gfp_crop);
                    Objects3DPopulation spots = getPopFromImage(rfp_crop);
                    // For spindle if more than one object take only the biggest
                    int index = 0;
                    if (popTmp.getNbObjects() > 1) {
                        double volume = 1000;   // minimum size for spindle
                        for (int i = 0; i < popTmp.getNbObjects(); i++) {
                            if (popTmp.getObject(i).getVolumeUnit() > volume) {
                                volume = popTmp.getObject(i).getVolumeUnit();
                                index = i;
                            }
                        }
                        IJ.log("index = " + index);
                        IJ.log("volume = " + popTmp.getObject(index).getVolumeUnit());
                    }
                    // Compute infos if some objects exist and size >= 10000       
                    IJ.log("Nbre spots "+spots.getNbObjects());
                    if ((popTmp.getNbObjects() > 0) && (spots.getNbObjects() > 0)) {
                        if (index >= 0) {
                            Object3D spindle = popTmp.getObject(index);
                            IJ.showStatus("computing distances ...");
                            computeInfo(gfp_crop, spindle, spots, outputDistAnalyze, imageDir, imageName);
                        }

                        // EVF = EDT normalisee, inside spindle 
                        // EVF_info_spindle(gfp_crop, spindle, spots);
                        // EVF_info_poles(gfp_crop, spindle, spots);
                    }
                    gfp_crop.flush();
                    rfp_crop.flush();
                }
            }
            outputDistAnalyze.close();
            IJ.showStatus("Finished");
        } catch (IOException ex) {
            Logger.getLogger(Ovocyte_Mtocs.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private Objects3DPopulation getPopFromImage(ImagePlus img) {
        // label binary images first
        ImageLabeller labeller = new ImageLabeller();
        ImageInt labels = labeller.getLabels(ImageHandler.wrap(img));
        labels.setCalibration(img.getCalibration());
        Objects3DPopulation pop = new Objects3DPopulation(labels);
        return pop;
    }

    private void computeInfo(ImagePlus imgSpindle, Object3D spindle, Objects3DPopulation mtocs, BufferedWriter results, String inDir, String image) throws IOException {
        // EVF spindle info 
        ImageInt img = ImageInt.wrap(imgSpindle);
        ImageFloat edtSpindle = EDT.run(img, 128, (float) cal.pixelWidth, (float) cal.pixelDepth, false, 0);
        EDT.normalizeDistanceMap(edtSpindle, img, true);

        // EVF Mtocs info
        Voxel3D Feret1 = spindle.getFeretVoxel1();
        Voxel3D Feret2 = spindle.getFeretVoxel2();
        ImageHandler poles = img.createSameDimensions();
        poles.setPixel(Feret1, 255);
        poles.setPixel(Feret2, 255);
        ImageFloat edtMtocs = EDT.run(poles, 128, (float) cal.pixelWidth, (float) cal.pixelDepth, true, 0);
        EDT.normalizeDistanceMap(edtMtocs, img, true);

        double Feret_length = Feret1.distance(Feret2, cal.pixelWidth,cal.pixelDepth);
        IJ.log("Image: " + image);
        IJ.log("Feret :" + Feret1 + " " + Feret2 + "\n Spindle volume : " + spindle.getVolumeUnit());
        IJ.log("Feret_lenght : " + Feret_length);
        IJ.log("#Mtocs : " + mtocs.getNbObjects());

        ImageHandler imgObjects = img.createSameDimensions();
        spindle.draw(imgObjects, 64);
        for (int i = 0; i < mtocs.getNbObjects(); i++) {
            // nbre de pixel colocalise 
            IJ.log(i+" "+spindle.getColoc(mtocs.getObject(i)));
            if (spindle.getColoc(mtocs.getObject(i)) > 10) {
                IJ.log("Mtocs volume :" + mtocs.getObject(i).getVolumeUnit());
                double distBorder = mtocs.getObject(i).distBorderUnit(spindle);
                IJ.log("spot to spindle border to border " + i + " : " + distBorder);
                double distCenter = mtocs.getObject(i).distCenterBorderUnit(spindle);
                IJ.log("spot to spindle center to border " + i + " : " + distCenter);
                double dist1 = mtocs.getObject(i).distPixelCenter(Feret1.x, Feret1.y, Feret1.z);
                double dist2 = mtocs.getObject(i).distPixelCenter(Feret2.x, Feret2.y, Feret2.z);
                IJ.log("spot to poles " + i + " : " + dist1 + " " + dist2);
                IJ.log("spot to poles " + i + " : " + Math.min(dist1, dist2));
                double EVFSpindle = edtSpindle.getPixel(mtocs.getObject(i).getCenterAsPoint());
                IJ.log("EVF mtocs spindle " + i + " : " + EVFSpindle);
                double EVFMtocs = edtMtocs.getPixel(mtocs.getObject(i).getCenterAsPoint());
                IJ.log("EVF mtocs spindle " + i + " : " + EVFMtocs);
                results.write(image + "\t" + spindle.getVolumeUnit() + "\t" + Feret_length + "\t" + mtocs.getObject(i).getVolumeUnit() + "\t"
                        + Math.min(dist1, dist2) + "\t" + distBorder + "\t" + distCenter + "\t" + EVFSpindle + "\t" + EVFMtocs + "\n");
                results.flush();
                mtocs.getObject(i).draw(imgObjects, 255);
            }
        }
        FileSaver objectsFile = new FileSaver(imgObjects.getImagePlus());
        objectsFile.saveAsTiffStack(inDir + image + "_objects.tif");
    }

//    private void EVF_info_spindle(ImagePlus spindle, Object3D spi, Objects3DPopulation mtocs) {
//        ImageInt img = ImageInt.wrap(spindle);
//        ImageFloat edt = EDT.run(img, 128, (float) cal.pixelWidth, (float) cal.pixelDepth, false, 0);
//        EDT.normalizeDistanceMap(edt, img, true);
//        for (int i = 0; i < mtocs.getNbObjects(); i++) {
//            if (spi.includes(mtocs.getObject(i))) {
//                IJ.log("EVF mtocs spindle " + i + " : " + edt.getPixel(mtocs.getObject(i).getCenterAsPoint()));
//            }
//        }
//    }
//
//    private void EVF_info_poles(ImagePlus spindle, Object3D spi, Objects3DPopulation mtocs) {
//        ImageInt img = ImageInt.wrap(spindle);
//        Voxel3D Feret1 = spi.getFeretVoxel1();
//        Voxel3D Feret2 = spi.getFeretVoxel2();
//        ImageHandler poles = img.createSameDimensions();
//        poles.setPixel(Feret1, 255);
//        poles.setPixel(Feret2, 255);
//        ImageFloat edt = EDT.run(poles, 128, (float) cal.pixelWidth, (float) cal.pixelDepth, true, 0);
//        EDT.normalizeDistanceMap(edt, img, true);
//        //edt.show("EVF_poles");
//        for (int i = 0; i < mtocs.getNbObjects(); i++) {
//            if (spi.includes(mtocs.getObject(i))) {
//                IJ.log("EVF mtocs poles " + i + " : " + edt.getPixel(mtocs.getObject(i).getCenterAsPoint()));
//            }
//        }
//    }
}
