package spotsAnalyze;

//This plugin take XML file from TrackMAte
//   and measure the distance to cortex and calculate spot speed
//   Need to ask drawing of the bouding box of the cell


import ij.IJ;
import ij.ImagePlus;
import ij.gui.WaitForUserDialog;
import ij.io.Opener;
import ij.plugin.PlugIn;
import java.io.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import ij.gui.*;
import ij.measure.Calibration;
import java.awt.Polygon;



public class Spots_Analyze implements PlugIn {
	
    

    boolean canceled =false;
    Calibration cal = new Calibration();
    String frameIntervalUnits;
    String spaceUnits;
    ArrayList<track_XML> tracks = new ArrayList<track_XML>();
    ArrayList<spots_XML> spots = new ArrayList<spots_XML>();
    
    public double calculateDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow((x2 - x1), 2) + Math.pow((y2 - y1), 2));
    }
    
    public double pointToLineDistance(double pxA, double pyA, double pxB, double pyB, double px, double py) {
        double normalLength = calculateDistance(pxA, pyA, pxB, pyB);
        return Math.abs((px - pxA) * (pyB - pyA) - (py - pyA) * (pxB - pxA)) / normalLength;
    }
    
    public double distanceToCortex(double px, double py, Polygon cortex) {
       
       double distance = 0;
       double d1, d2;
       for(int p = 0; p < cortex.npoints-2; p++) {
           double px1 = cortex.xpoints[p]*cal.pixelWidth;
           double py1 = cortex.ypoints[p]*cal.pixelWidth;
           double px2 = cortex.xpoints[p+1]*cal.pixelWidth;
           double py2 = cortex.ypoints[p+1]*cal.pixelWidth;
           d1 = pointToLineDistance(px1, py1, px2, py2, px, py);
           px1 = px2;
           py1 = py2;
           px2 = cortex.xpoints[p+2]*cal.pixelWidth;
           py2 = cortex.ypoints[p+2]*cal.pixelWidth;
           d2 = pointToLineDistance(px1, py1, px2, py2, px, py);
           if (d1 < d2) distance = d1;
           else distance = d2;
       }
        return(distance);
    }
    
    public double angleLink(double pxA, double pyA, double pxB, double pyB, double pxC, double pyC){
        double angle; 
        double vecABx = pxB - pxA;
        double vecABy = pyB - pyA;
        double vecBCx = pxC - pxB;
        double vecBCy = pyC - pyB;

        // Angle between n and n+1 links               
        angle = Math.atan2(vecBCy,vecBCx) - Math.atan2(vecABy,vecABx);
        return angle;
    }
    
    
    public void parseXML (String dir, String file) throws FileNotFoundException, IOException{
    
        BufferedReader br = new BufferedReader(new FileReader(dir+file));
        double xt0 = 0, yt0 = 0, xtn = 0, ytn = 0;
        double Xt0 = 0, Yt0 = 0;
        double spotSpeed;
        double linkLength;
        double trackLength;
        
        int nbSpots =0, nTrack = 0;
        String strLine;
        String[] line;
        String particle = "  <particle";
        double frameInterval = 0;
        
        while ((strLine = br.readLine()) != null)   { 
            if (strLine.startsWith("<Tracks")) {
                line = strLine.split("\"");
                spaceUnits = line[3];
                frameInterval = Double.valueOf(line[5]);
                frameIntervalUnits = line[7];
            }
            if (strLine.startsWith(particle)) { // find <particle nSpots="xx">
                linkLength = 0;
                trackLength = 0;
                nTrack++;
                line = strLine.split("\"");
                nbSpots = Integer.valueOf(line[1]);
                
                for (int i = 1; i <= nbSpots; i++) {     // compute spots speed
                    strLine = br.readLine();            // read next line find t=0 spot coords
                    line = strLine.split("\"");
                    if (i == 1) {   // first point of track
                        xt0 = Double.valueOf(line[3]);
                        yt0 = Double.valueOf(line[5]);
                        Xt0 = xt0;
                        Yt0 = yt0;
                    }
                    else {
                        xtn = Double.valueOf(line[3]);
                        ytn = Double.valueOf(line[5]);
                        linkLength = calculateDistance(xt0, yt0, xtn, ytn);
                        trackLength += linkLength;
                        spotSpeed = linkLength/frameInterval;
                        spots_XML spot_type = new spots_XML(xt0, yt0, xtn, ytn, spotSpeed);
                        spots.add(spot_type);
                        xt0 = xtn;
                        yt0 = ytn;
                    }   
                }
                track_XML track_type = new track_XML(nTrack, nbSpots, Xt0, Yt0, xtn, ytn, trackLength);
                tracks.add(track_type);
            }
        }
    }
    
    
    
    public boolean AskToDrawRoi(String imageDir, String imageName) throws IOException {
        boolean ok = false;
        Opener imgOpener = new Opener();

        double cxDistPn = 0;    // distance from last pointto cortex
        double angle = 0;
        double tortuosity = 0;
        int ntrack, nbspots;
        double trackLength;
        // write headers results
        FileWriter fw = new FileWriter(imageDir + imageName + ".xls",false);
        BufferedWriter output = new BufferedWriter(fw);
        output.write("#\tSpeed("+spaceUnits+"/"+frameIntervalUnits+")\tAngle(radians)\tDistance to cortex Pn("+spaceUnits+")\tTortuosity\n");
        
        ImagePlus img = imgOpener.openImage(imageDir + imageName);
        img.show();
    // Ask for drawing cortex limits and find speed of spots

        IJ.setTool(5); // "Segmented Line"
        new WaitForUserDialog("Wait For User","Draw cortex limit with segmented line\nthen press OK").show();

        if (img.getRoi() == null ) {
            IJ.showMessage("Error", "No Roi found !!");
            img.changes =false;
            img.close();
            ok = false;
        } 
        else {
            cal = img.getCalibration();
            Roi roiCortex = img.getRoi();
            Polygon cortexPoly = roiCortex.getPolygon();
            int indexFirst = 0, indexEnd = 0;
            double px2 = 0, py2 = 0;
            for (int t = 0; t < tracks.size(); t++) {
                ntrack = tracks.get(t).ntrack;
                trackLength = tracks.get(t).tracklength;
                nbspots = tracks.get(t).nbspots;
                double ptx0 = tracks.get(t).pt0x;
                double pty0 = tracks.get(t).pt0y;
                double ptx = tracks.get(t).ptx;
                double pty = tracks.get(t).pty;
                indexEnd += nbspots -1 ;
                for (int s = indexFirst; s < indexEnd; s++) { 
                    double px0 = spots.get(s).p0x;
                    double py0 = spots.get(s).p0y;
                    double px1 = spots.get(s).pnx;
                    double py1 = spots.get(s).pny; 
                    // Distance from last point of link to cortex
                    cxDistPn = distanceToCortex(px1, py1, cortexPoly);
                    // angle between links
                    if (s < indexEnd - 1) {
                        px2 = spots.get(s+1).pnx;
                        py2 = spots.get(s+1).pny;
                        angle = angleLink(px0, py0, px1, py1, px2, py2);
                    }
                    indexFirst = indexEnd;
                    tortuosity = calculateDistance(ptx0, pty0, ptx, pty) / trackLength;    
                    output.write(ntrack + "\t" + spots.get(s).spotspeed + "\t" + angle + "\t" + cxDistPn + "\t" + tortuosity + "\n");
                    output.flush();       
                }   
            }
            output.close();
            ok = true;
        }
        img.close();
        return(ok);
    }
 
    
    
    public void analyzeSpots(String inDir, String [] imageFile) throws IOException {
           
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

// find spots coordinates
           xmlFile = fileName + "_Tracks.xml";
           parseXML(inDir,xmlFile);  // parse xml file
// Open imageStack and ask for bounding box
           tifFile = fileName + ".tif";
           File f = new File(inDir + tifFile);
           if (f.exists()) {
                if (!AskToDrawRoi(inDir, tifFile)) return;               
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
// get all files from the directory
        
        String inDir = IJ.getDirectory("Choose source folder for tif and xml files ...");
        if (inDir == null) return;
        String [] tifFile = new File(inDir).list();
        if (tifFile == null) return;
        try {
            analyzeSpots(inDir, tifFile);
        } catch (IOException ex) {
            Logger.getLogger(Spots_Analyze.class.getName()).log(Level.SEVERE, null, ex);
        }
        IJ.showStatus("End of process");
    } 

    

}
