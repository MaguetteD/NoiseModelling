/**
 * NoiseModelling is a library capable of producing noise maps. It can be freely used either for research and education, as well as by experts in a professional use.
 * <p>
 * NoiseModelling is distributed under GPL 3 license. You can read a copy of this License in the file LICENCE provided with this software.
 * <p>
 * Official webpage : http://noise-planet.org/noisemodelling.html
 * Contact: contact@noise-planet.org
 */


package org.noise_planet.noisemodelling.pathfinder.profilebuilder;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.triangulate.quadedge.Vertex;

import java.util.List;
import java.util.ArrayList;


public class CurvedProfileGenerator {

    // Method to transform a list of flat coordinates to curved coordinates

    /**
     * Salomons, E., Van Maercke, D., Defrance, J., & De Roo, F. (2011). The Harmonoise sound propagation model. Acta acustica united with acustica, 97(1), 62-74.
     * @param flatProfile
     * @return
     */

    public static List<CutPoint> applyTransformation(List<CutPoint> flatProfile) {

        // Get chord endpoints
        CutPoint sourcePoint = flatProfile.get(0);
        CutPoint receiverPoint = flatProfile.get(flatProfile.size() - 1);
        Coordinate cs = sourcePoint.getCoordinate();
        Coordinate cr = receiverPoint.getCoordinate();
        double hs = cs.getZ()-sourcePoint.zGround;
        double hr = cr.getZ()-receiverPoint.zGround;

        // Radius of curvature
        double R_c = 2 * Math.max(1000, 8 * cs.distance(cr));

        List<CutPoint> curvedProfile = new ArrayList<>();
        int k=0;
        double translation =0;
        for (CutPoint point : flatProfile) {
            Coordinate coord = point.getCoordinate();
            // Extract flat coordinates (x, y, z)
            double x = coord.getX();
            double y = coord.getY();  // For 3D case, the y value can be included.
            double z = coord.getZ();
            double zGround = point.getzGround();

            double C0 = 2*(((hs+hr)/2)+R_c);

            double xpp = x - (cs.x+cr.x)/2;
            double ypp = y - (cs.y+cr.y)/2;
            double zpp = z - (cs.z+cr.z)/2;
            double zGpp = zGround - (cs.z+cr.z)/2;


            double xpp2 = xpp*xpp;
            double ypp2 = ypp*ypp;
            double zpp2 = zpp*zpp;
            double zGpp2 = zGpp*zGpp;
            double C02 = C0  *C0;

            double xp = (C02*xpp)/(xpp2+((C0+zpp)*(C0+zpp)));
            double yp = (C02*ypp)/(ypp2+((C0+zpp)*(C0+zpp)));
            double zp = (C0*(xpp2+ypp2+zpp2+zpp*C0))/(xpp2+ypp2+((C0+zpp)*(C0+zpp)));
            double zGp = (C0*(xpp2+ypp2+zGpp2+zGpp*C0))/(xpp2+ypp2+((C0+zGpp)*(C0+zGpp)));

            if (k==0) translation = zp -z;

            // Create new coordinate (x and y nearly unchanged for large R)
            CutPoint curvedCutPoint = new CutPoint(point);
            curvedCutPoint.setZGround(zGp-translation);
            curvedCutPoint.setCoordinate(new Coordinate(x,y,zp-translation));
            point.setCoordinate(curvedCutPoint.coordinate);
            point.setZGround(curvedCutPoint.zGround);
            curvedProfile.add(curvedCutPoint);
            k++;
        }

        return curvedProfile;
    }

    public static void applyTransformationScene(ProfileBuilder profileBuilder,Coordinate s,Coordinate r){
        // Get chord endpoints
        //ProfileBuilder existingBuilder = ProfileBuilder();
        Coordinate cs = s;
        Coordinate cr = r;
        double hs = cs.getZ()-profileBuilder.getZGround(s);
        double hr = cr.getZ()-profileBuilder.getZGround(r);  // todo get zground

        // Radius of curvature
        double R_c = 2 * Math.max(1000, 8 * cs.distance(cr));

        int k=0;
        double translation =0;
        for(int i = 0;i<profileBuilder.getProcessedWalls().size(); i++){
            if(i ==0 || (i >0 && !profileBuilder.getProcessedWalls().get(i-1).p1.equals(profileBuilder.getProcessedWalls().get(i).p0))){
                Coordinate coord = profileBuilder.getProcessedWalls().get(i).p0;
                Coordinate nextPoint = profileBuilder.getProcessedWalls().get(i).p1;
                // Extract flat coordinates (x, y, z)
                double x = coord.getX();
                double y = coord.getY();  // For 3D case, the y value can be included.
                double z = coord.getZ();
                double zGround = Vertex.interpolateZ(coord,s,nextPoint);//profileBuilder.getZGround(coord);
//                double zground  = Vertex.interpolateZ(cutPoint.coordinate, new Coordinate(previousZGround.coordinate.x, previousZGround.coordinate.y,
//                                previousZGround.getzGround()),
//                        new Coordinate(nextPoint.coordinate.x, nextPoint.coordinate.y, nextPoint.getzGround()));

                double C0 = 2 * (((hs + hr) / 2) + R_c);

                double xpp = x - (cs.x + cr.x) / 2;
                double ypp = y - (cs.y + cr.y) / 2;
                double zpp = z - (cs.z + cr.z) / 2;
                double zGpp = zGround - (cs.z + cr.z) / 2;


                double xpp2 = xpp * xpp;
                double ypp2 = ypp * ypp;
                double zpp2 = zpp * zpp;
                double zGpp2 = zGpp * zGpp;
                double C02 = C0 * C0;

                double xp = (C02 * xpp) / (xpp2 + ((C0 + zpp) * (C0 + zpp)));
                double yp = (C02 * ypp) / (ypp2 + ((C0 + zpp) * (C0 + zpp)));
                double zp = (C0 * (xpp2 + ypp2 + zpp2 + zpp * C0)) / (xpp2 + ypp2 + ((C0 + zpp) * (C0 + zpp)));
                double zGp = (C0 * (xpp2 + ypp2 + zGpp2 + zGpp * C0)) / (xpp2 + ypp2 + ((C0 + zGpp) * (C0 + zGpp)));

                if (k == 0) translation = zp - z;

                // Create new coordinate (x and y nearly unchanged for large R)
                profileBuilder.getProcessedWalls().get(i).height = zGp - translation;
                profileBuilder.getProcessedWalls().get(i).p0.setCoordinate(new Coordinate(x, y, zp - translation));

                coord = profileBuilder.getProcessedWalls().get(i).p1;
                // Extract flat coordinates (x, y, z)
                x = coord.getX();
                y = coord.getY();  // For 3D case, the y value can be included.
                z = coord.getZ();
                zGround = profileBuilder.getZGround(coord);//walls.get(i).getHeight(); todo get zground

                C0 = 2 * (((hs + hr) / 2) + R_c);

                xpp = x - (cs.x + cr.x) / 2;
                ypp = y - (cs.y + cr.y) / 2;
                zpp = z - (cs.z + cr.z) / 2;
                zGpp = zGround - (cs.z + cr.z) / 2;


                xpp2 = xpp * xpp;
                ypp2 = ypp * ypp;
                zpp2 = zpp * zpp;
                zGpp2 = zGpp * zGpp;
                C02 = C0 * C0;

                xp = (C02 * xpp) / (xpp2 + ((C0 + zpp) * (C0 + zpp)));
                yp = (C02 * ypp) / (ypp2 + ((C0 + zpp) * (C0 + zpp)));
                zp = (C0 * (xpp2 + ypp2 + zpp2 + zpp * C0)) / (xpp2 + ypp2 + ((C0 + zpp) * (C0 + zpp)));
                zGp = (C0 * (xpp2 + ypp2 + zGpp2 + zGpp * C0)) / (xpp2 + ypp2 + ((C0 + zGpp) * (C0 + zGpp)));

                if (k == 0) translation = zp - z;

                // Create new coordinate (x and y nearly unchanged for large R)
                profileBuilder.getProcessedWalls().get(i).height = zGp - translation; // todo check if we modified wall's height
                profileBuilder.getProcessedWalls().get(i).p1.setCoordinate(new Coordinate(x, y, zp - translation));
            }else {
                Coordinate coord = profileBuilder.getProcessedWalls().get(i).p1;
                // Extract flat coordinates (x, y, z)
                double x = coord.getX();
                double y = coord.getY();  // For 3D case, the y value can be included.
                double z = coord.getZ();
                double zGround = profileBuilder.getZGround(coord);//walls.get(i).getHeight();

                double C0 = 2 * (((hs + hr) / 2) + R_c);

                double xpp = x - (cs.x + cr.x) / 2;
                double ypp = y - (cs.y + cr.y) / 2;
                double zpp = z - (cs.z + cr.z) / 2;
                double zGpp = zGround - (cs.z + cr.z) / 2;


                double xpp2 = xpp * xpp;
                double ypp2 = ypp * ypp;
                double zpp2 = zpp * zpp;
                double zGpp2 = zGpp * zGpp;
                double C02 = C0 * C0;

                double xp = (C02 * xpp) / (xpp2 + ((C0 + zpp) * (C0 + zpp)));
                double yp = (C02 * ypp) / (ypp2 + ((C0 + zpp) * (C0 + zpp)));
                double zp = (C0 * (xpp2 + ypp2 + zpp2 + zpp * C0)) / (xpp2 + ypp2 + ((C0 + zpp) * (C0 + zpp)));
                double zGp = (C0 * (xpp2 + ypp2 + zGpp2 + zGpp * C0)) / (xpp2 + ypp2 + ((C0 + zGpp) * (C0 + zGpp)));

                if (k == 0) translation = zp - z;

                // Create new coordinate (x and y nearly unchanged for large R)
                profileBuilder.getProcessedWalls().get(i).height = zGp - translation;
                profileBuilder.getProcessedWalls().get(i).p1.setCoordinate(new Coordinate(x, y, zp - translation));
            }
                k++;

        }


    }



    /**
     * Curves the flat profile based on the source-receiver distance.
     * @param flatProfile The original flat profile as a list of (x, y) points.
     * @param sourceReceiverDistance The distance between the source and receiver.
     * @return A new list of points representing the curved profile.
     */
    public static List<CutPoint> curveProfile(List<CutPoint> flatProfile, double sourceReceiverDistance) {

        double R = 2 * Math.max(1000, 8 * sourceReceiverDistance);

        List<CutPoint> curvedProfile = new ArrayList<>();
        if (flatProfile == null || flatProfile.size() < 2) return curvedProfile;
        // Get chord endpoints
        CutPoint sourcePoint = flatProfile.get(0);
        CutPoint receiverPoint = flatProfile.get(flatProfile.size() - 1);
        Coordinate c1 = sourcePoint.getCoordinate();
        Coordinate cn = receiverPoint.getCoordinate();

        // Chord vector and length
        double dx = cn.x - c1.x;
        double dy = cn.y - c1.y;
        double dz = cn.z - c1.z;
        double chordLength = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // Chord direction (unit vector)
        double ux = dx / chordLength;
        double uy = dy / chordLength;
        double uz = dz / chordLength;

        // For vertical curvature, assume bending is downward in z
        for (CutPoint point : flatProfile) {
            Coordinate c = point.getCoordinate();
            // Project point onto chord (get its fractional position t in [0,1])
            double px = c.x - c1.x;
            double py = c.y - c1.y;
            double pz = c.z - c1.z;
            double proj = px * ux + py * uy + pz * uz; // Scalar projection
            double t = proj / chordLength;

            // Compute (x, y, z) along straight chord for t
            double flatX = c1.x + t * dx;
            double flatY = c1.y + t * dy;
            double flatZ = c.z ;

            // Compute horizontal offset from chord midpoint for sagitta
            double alongArc = t * chordLength;
            double offsetFromMid = alongArc - chordLength / 2.0;
            double sagitta = R - Math.sqrt(R * R - offsetFromMid * offsetFromMid);

            // Bend "down" in z
            double curvedZ = flatZ + sagitta;

            // Create new coordinate (x and y nearly unchanged for large R)
            Coordinate curvedCoord = new Coordinate(flatX, flatY, curvedZ);
            CutPoint curvedCutPoint = point;
            point.setCoordinate(curvedCoord);
            curvedProfile.add(curvedCutPoint);
        }
        return curvedProfile;
    }

}

