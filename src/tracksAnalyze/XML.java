package tracksAnalyze;


/**
 *
 * @author phm
 */
public class XML {        
double px0, py0;    //gives the cartesian coordinates of first spot in track
double pxn, pyn;    //gives the cartesian coordinates of last spot in track
int t0, tn;         // time positions for first and last spot
double trackLength;    // length of track
boolean center;     // go to center

    public XML(int time0, double xcoor0, double ycoor0, int timeN, double xcoorN, double ycoorN, double lengthTrack, boolean goCenter) {
            t0 = time0;
            px0 = xcoor0;
            py0 = ycoor0;
            tn = timeN; 
            pxn = xcoorN;
            pyn = ycoorN;
            trackLength = lengthTrack;
            center = goCenter;
        }
        
    }
