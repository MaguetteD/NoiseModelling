/**
 * NoiseModelling is a library capable of producing noise maps. It can be freely used either for research and education, as well as by experts in a professional use.
 * <p>
 * NoiseModelling is distributed under GPL 3 license. You can read a copy of this License in the file LICENCE provided with this software.
 * <p>
 * Official webpage : http://noise-planet.org/noisemodelling.html
 * Contact: contact@noise-planet.org
 */


package org.noise_planet.noisemodelling.propagation;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.noise_planet.noisemodelling.pathfinder.profilebuilder.CutPoint;
import org.noise_planet.noisemodelling.pathfinder.profilebuilder.CurvedProfileGenerator;

import java.util.ArrayList;
import java.util.List;

public class CurvedProfileTest {

    @Test
    public void curvedProfile() {
        // Test the implementation with some dummy data
        List<CutPoint> flatProfile = new ArrayList<>();
        flatProfile.add(new CutPoint(new Coordinate(0, 0, 4)));
        flatProfile.add(new CutPoint(new Coordinate(500, 500, 1)));
        flatProfile.add(new CutPoint(new Coordinate(1000, 1000, 1)));
        flatProfile.add(new CutPoint(new Coordinate(1500, 1500, 1)));
        flatProfile.add(new CutPoint(new Coordinate(2000, 2000, 1)));
        flatProfile.add(new CutPoint(new Coordinate(2500, 2500, 4)));

        flatProfile.get(0).setZGround(0);
        flatProfile.get(1).setZGround(0);
        flatProfile.get(2).setZGround(0);
        flatProfile.get(3).setZGround(0);
        flatProfile.get(4).setZGround(0);
        flatProfile.get(5).setZGround(0);


        double distance = flatProfile.get(0).getCoordinate().distance(flatProfile.get(5).getCoordinate());
        System.out.println("distance = " + distance);
        CurvedProfileGenerator generator = new CurvedProfileGenerator();
        List<CutPoint> curvedProfile = CurvedProfileGenerator.applyTransformation(flatProfile);

        // Print the transformed curved coordinates
        System.out.println("Apply curved profile transformation(cutProfile):");
        for (CutPoint point : curvedProfile) {
            Coordinate coord = point.getCoordinate();
            System.out.println("Transformed coordinate: x = " + coord.getX() + ", y = " + coord.getY() + ", z = " + coord.getZ()+ ", zG = " + point.zGround);
        }

    }

   // @Test
//    public void curvedProfileScene() {
//        // Test the implementation with some dummy data
//        List<Coordinate> flatProfile = new ArrayList<>();
//        flatProfile.add(new Coordinate(0, 0, 4));
//        flatProfile.add(new Coordinate(500, 500, 1));
//        flatProfile.add(new Coordinate(1000, 1000, 1));
//        flatProfile.add(new Coordinate(1500, 1500, 1));
//        flatProfile.add(new Coordinate(2000, 2000, 1));
//        flatProfile.add(new Coordinate(2500, 2500, 4));
//
//
//
//
//        double distance = flatProfile.get(0).distance(flatProfile.get(5));
//        System.out.println("distance = " + distance);
//        CurvedProfileGenerator generator = new CurvedProfileGenerator();
//        CurvedProfileGenerator.profileTest(flatProfile.subList(1, 3),flatProfile.get(0),flatProfile.get(5));
//        CurvedProfileGenerator.profileTest(flatProfile.subList(3, 5),flatProfile.get(0),flatProfile.get(5));
//
//        // Print the transformed curved coordinates
//        System.out.println("Apply curved wall transformation:");
//        for (Coordinate coord : flatProfile) {
//            //Coordinate coord = point.getCoordinate();
//            System.out.println("Transformed coordinate: x = " + coord.getX() + ", y = " + coord.getY() + ", z = " + coord.getZ());
//        }
//
//    }



}
