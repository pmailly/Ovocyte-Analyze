package tracksAnalyze;


//This plugin take XML file from TrackMAte
//   and measure the distance of end point track to cell border
//   Need to ask drawing of the bouding box of the cell


import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.WaitForUserDialog;
import ij.io.Opener;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.plugin.filter.Analyzer;
import java.io.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import ij.gui.*;
import ij.io.FileSaver;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import java.awt.Color;


public class Tracks_Analyze implements PlugIn {
	
    boolean canceled =false;
    double xCent, yCent, xRect, yRect, wRect;   // Bounding box coordinates
    double cellRadius;
    double d0, dn;                              // edge distance at t0 and tn
    String direction;                               // edge direction
    double distPercent;                         // percentage of displacement relative to cell radius
    double tortuosity;
    
    public ArrayList<XML> parse_xml(String dir, String file) throws FileNotFoundException, IOException{
        
        ArrayList<XML> spots = new ArrayList<XML>();  
        BufferedReader br = new BufferedReader(new FileReader(dir+file));
        double xt0 = 0, yt0 = 0, xtend = 0, ytend = 0, xtn = 0, ytn = 0;
        int t0 = 0, tend = 0;
        double trackLength;
        boolean center = false;
        int nbSpots;
        String strLine;
        String[] line;
        String particle = "  <particle";
        
        while ((strLine = br.readLine()) != null)   { 
            if (strLine.startsWith(particle)) { // find <particle nSpots="xx">
                trackLength = 0;
                line = strLine.split("\"");
                nbSpots = Integer.valueOf(line[1]);
                for (int i = 1; i <= nbSpots; i++) {     // compute track length
                    strLine = br.readLine();            // read next line find t=0 spot coords
                    line = strLine.split("\"");
                    if (i == 1) {   // first point of track
                        t0 = Integer.valueOf(line[1]);
                        xt0 = Double.valueOf(line[3]);
                        yt0 = Double.valueOf(line[5]);
                        xtn = xt0;
                        ytn = yt0;
                    }
                    else if ( i == nbSpots) {    // last point of track
                        tend = Integer.valueOf(line[1]);
                        xtend = Double.valueOf(line[3]);
                        ytend = Double.valueOf(line[5]);
                    }
                    else {
                        trackLength += calculateDistance(xtn, ytn, Double.valueOf(line[3]), Double.valueOf(line[5]));
                        xtn = Double.valueOf(line[3]);
                        ytn = Double.valueOf(line[5]);
                    }
                }              
                XML type = new XML(t0, xt0, yt0, tend, xtend, ytend, trackLength, center);
                spots.add(type);
            }
        }
        return(spots); 
    }
    
    public double calculateDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow((x2 - x1), 2) + Math.pow((y2 - y1), 2));
    }
    
    public boolean AskToDrawRoi(String imageDir, String imageName, ArrayList<XML> points) throws IOException {
        boolean ok = false;
        Opener imgOpener = new Opener();
        ImagePlus img = imgOpener.openImage(imageDir + imageName);
        
        img.show();
    // Ask for drawing boundingbox of cell and find boundingbox coordinates
        ResultsTable rt = new ResultsTable();
        Analyzer analyze = new Analyzer(img);
        new WaitForUserDialog("Wait For User","Draw the bounding box of the cell\nthen press OK").show();
        if (img.getRoi() == null ) {
            IJ.showMessage("Error", "No Roi found !!");
            img.changes =false;
            img.close();
            ok = false;
        } 
        else {
            analyze.measure();
            rt = ResultsTable.getResultsTable();
            xCent = rt.getValue("X",0);
            yCent = rt.getValue("Y",0);
            xRect = rt.getValue("BX",0);
            yRect = rt.getValue("BY",0);
            wRect = rt.getValue("Width",0);
            cellRadius = Math.sqrt(Math.pow((xRect + wRect/2) - xCent,2) + Math.pow(yRect - yCent, 2));

// write headers results
            FileWriter fw = new FileWriter(imageDir + imageName + ".xls",false);
            BufferedWriter output = new BufferedWriter(fw);
            output.write("T0\tTn\tD0\tDn\tDirection\t%Amoung\ttrack Length\tTortuosity\n");

// For each track compute distance to cell border and save in file
            for (int i = 0; i < points.size(); i++) {
                d0 = Math.abs(cellRadius - calculateDistance(points.get(i).px0, points.get(i).py0, xCent, yCent));
                dn = Math.abs(cellRadius - calculateDistance(points.get(i).pxn, points.get(i).pyn, xCent, yCent));
                if (d0 < dn) {
                    direction = "Center";
                    points.set(i,new XML(points.get(i).t0,  points.get(i).px0, points.get(i).py0, 
                        points.get(i).tn, points.get(i).pxn, points.get(i).pyn, points.get(i).trackLength, true));
                }
                else if (d0 > dn) {
                    direction = "Edge";
                    points.set(i,new XML(points.get(i).t0,  points.get(i).px0, points.get(i).py0, 
                        points.get(i).tn, points.get(i).pxn, points.get(i).pyn, points.get(i).trackLength,false));
                }
                else direction = "None";
                distPercent = Math.abs(d0 - dn)/cellRadius;
                tortuosity = calculateDistance(points.get(i).px0, points.get(i).py0, points.get(i).pxn, points.get(i).pyn) / points.get(i).trackLength;
                output.write(points.get(i).t0 + "\t" + points.get(i).tn + "\t" + d0 + "\t" + dn + "\t" + direction + "\t" 
                        + distPercent + "\t" + points.get(i).trackLength + "\t" + tortuosity + "\n");
                output.flush();
            }          
            output.close();
            drawArrows(points, img, imageDir, imageName);
            ok = true;
        }
        return(ok);
    }

    public void drawArrows (ArrayList<XML> points, ImagePlus img, String inDir, String imgName) {

        
        RoiManager rm = new RoiManager();
        double pixelWidth = img.getCalibration().pixelWidth;
        IJ.run("Z Project...", "start=1 stop=480 projection=[Max Intensity]");
        img.changes = false;
        img.close();
        ImagePlus imgProj = WindowManager.getCurrentImage();
        ImageProcessor ipProj = imgProj.getProcessor();

        for (int i = 0; i < points.size(); i++) {
            double x1 = points.get(i).px0/pixelWidth;
            double x2 = points.get(i).pxn/pixelWidth;
            double y1 = points.get(i).py0/pixelWidth;
            double y2 = points.get(i).pyn/pixelWidth;
            
            Arrow arrows = new Arrow(x1, y1, x2, y2);
            arrows.setHeadSize(10);
            
            if (points.get(i).center) {
                arrows.setStrokeColor(Color.RED); 
            }
            else {
                arrows.setStrokeColor(Color.BLUE);
            }
            imgProj.setRoi(arrows);
            imgProj.updateAndDraw();
            rm.add(imgProj, arrows, i); 
        }
        rm.runCommand("Show All");
        FileSaver imgSave = new FileSaver(imgProj);
        String roiName = imgName.substring(0, imgName.indexOf(".tif")) + "_" + "Roi" + ".tif";
        imgSave.saveAsTiff(inDir + roiName);
        imgProj.changes = false;
        imgProj.close();
        rm.close();
    }
    
    
    
    public void analyzeTracks(String inDir, String [] imageFile) throws IOException {
           
        ArrayList<String> nameWithOutExt = new ArrayList<String>();
        String xmlFile, tifFile;
        
        for (int n = 0; n < imageFile.length; n++) {
            // find XML file
            int dotposition= imageFile[n].lastIndexOf(".");
            String ext = imageFile[n].substring(dotposition+1, imageFile[n].length());
            if ("xml".equals(ext)) { 
                nameWithOutExt.add(imageFile[n].substring(0, dotposition - 7));
            }
        }
        if (nameWithOutExt.isEmpty()) { 
            IJ.showMessage("Error", "No xml file found !!");
            return;
        }
        for ( String fileName : nameWithOutExt ) {   
           ArrayList<XML> points = new ArrayList<XML>();
// find spots coordinates
           xmlFile = fileName + "_Tracks.xml";
           points = parse_xml(inDir,xmlFile);  // parse xml file
// Open imageStack and ask for bounding box
           tifFile = fileName + ".tif";
           File f = new File(inDir + tifFile);
           if (f.exists()) {
                if (!AskToDrawRoi(inDir, tifFile, points)) return;               
           }
           else {
               IJ.showMessage(tifFile + " not found in " + inDir + " !!");
               return;
           }
        }
    }   
    
    public void run(String arg) {
        
        if (canceled) {
            IJ.showMessage(" Pluging canceled");
            return;
        }
        IJ.run("Set Measurements...", "  centroid bounding redirect=None decimal=4");
// get all files from the directory
        
        String inDir = IJ.getDirectory("Choose source folder for tif and xml files ...");
        if (inDir == null) return;
        String [] tifFile = new File(inDir).list();
        if (tifFile == null) return;
        try {
            analyzeTracks(inDir, tifFile);
        } catch (IOException ex) {
            Logger.getLogger(Tracks_Analyze.class.getName()).log(Level.SEVERE, null, ex);
        }
        IJ.showStatus("End of process");
    } 

}
