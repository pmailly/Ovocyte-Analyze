package spotsAnalyze;

/**
 *
 * @author phm
 */


public class track_XML  {
        int ntrack;            // track number
        int nbspots;           // number of spots in track
        double pt0x,pt0y;               // first position in track
        double ptx,pty;               // last position in track
        double tracklength;     // length of track
        
        public track_XML(int nTrack, int nbSpots, double Pt0x, double Pt0y, double Ptx, double Pty, double trackLength) {
            ntrack = nTrack;
            nbspots = nbSpots;
            pt0x = Pt0x;
            pt0y = Pt0y;
            ptx = Ptx;
            pty = Pty;
            tracklength = trackLength;
        }
}