package spotsAnalyze;


/**
 *
 * @author phm
 */


public class spots_XML  {
        double p0x, p0y;        // first positions spots of track
        double pnx, pny;        // first  and next positions spots of track
        double spotspeed;     // length of track
        
        public spots_XML(double P0x, double P0y, double Pnx, double Pny, double spotSpeed) {
           p0x = P0x;
           p0y = P0y;
           pnx = Pnx;
           pny = Pny;
           spotspeed = spotSpeed;
        }
}