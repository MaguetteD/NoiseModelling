package org.noisemap.core;

import java.util.ArrayList;
import java.util.List;

import org.noisemap.core.FastObstructionTest;
import org.noisemap.core.LayerDelaunayError;


import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Polygon;

import junit.framework.TestCase;

public class TestFastObstruction extends TestCase {

	static boolean isBarelyEqual(double v1, double v2) {
		return Math.abs(v1 - v2) < 1e-7;
	}

	private void checkAngle(double angle1, double angle2) {
		assertTrue(Math.cos(angle1) + "!=" + Math.cos(angle2) + "(" + angle1
				+ "!=" + angle2 + ")",
				isBarelyEqual(Math.cos(angle1), Math.cos(angle2)));
		assertTrue(Math.sin(angle1) + "!=" + Math.sin(angle2) + "(" + angle1
				+ "!=" + angle2 + ")",
				isBarelyEqual(Math.sin(angle1), Math.sin(angle2)));
	}

	private void checkMerge(double[] ccw_values, double assertCCW1,
			double assertCCW2) {
		ArrayList<Double> assertShortcut;
		ArrayList<ArrayList<Double>> verticesAngle = new ArrayList<ArrayList<Double>>();
		for (int rangeid = 0; rangeid < ccw_values.length - 1; rangeid += 2) {
			verticesAngle.add(new ArrayList<Double>());
			FastObstructionTest.updateMinMax(
					0,
					new Coordinate(0, 0),
					new Coordinate(Math.cos(ccw_values[rangeid]), Math
							.sin(ccw_values[rangeid])),
					new Coordinate(Math.cos(ccw_values[rangeid + 1]), Math
							.sin(ccw_values[rangeid + 1])), verticesAngle);
		}
		assertShortcut = verticesAngle.get(0);
		assertTrue("Merging of open angle failed, too many elements !",
				assertShortcut.size() == 2);
		checkAngle(assertShortcut.get(0), assertCCW1);
		checkAngle(assertShortcut.get(1), assertCCW2);
	}

	/**
	 * Test classification/fusion of open angles (help to compute translation
	 * epsilon of diffraction edges)
	 */
	public void testAngleOrdering() {
		// Test case merge of angle ranges, cover all cases
		double[] values1 = { (7. / 4.) * Math.PI, (1. / 4.) * Math.PI,
				(1. / 4.) * Math.PI, (3. / 4.) * Math.PI, (3. / 4.) * Math.PI,
				(5. / 4.) * Math.PI };
		checkMerge(values1, values1[0], values1[5]);
		double[] values2 = { (7. / 4.) * Math.PI, (1. / 4.) * Math.PI,
				(3. / 4.) * Math.PI, (5. / 4.) * Math.PI, (1. / 4.) * Math.PI,
				(3. / 4.) * Math.PI };
		checkMerge(values2, values2[0], values2[3]);
		double[] values3 = { (7. / 4.) * Math.PI, (1. / 4.) * Math.PI,
				(3. / 4.) * Math.PI, (5. / 4.) * Math.PI, (1. / 4.) * Math.PI,
				(3. / 4.) * Math.PI, (5. / 4.) * Math.PI, (7. / 4.) * Math.PI };
		checkMerge(values3, values3[3], values3[3]);
		double[] values4 = { -1.08, -0.357, 1.86, -2.98, 0.424, 1.86, -0.357,
				0.424 };
		checkMerge(values4, values4[0], values4[3]);
		double[] values5 = { -1.932, -1.172, -2.693, -1.932, 1.847, 1.999,
				0.691, 1.847, -1.172, 0.396, 0.396, 0.691 };
		checkMerge(values5, values5[2], values5[5]);

	}
	public void testScene1() throws LayerDelaunayError {
		
		//Build Scene with One Building
		GeometryFactory factory = new GeometryFactory();
		Coordinate[] building1Coords = { new Coordinate(15., 5.,0.),
				new Coordinate(30., 5.,0.), new Coordinate(30., 30.,0.),
				new Coordinate(15., 30.,0.), new Coordinate(15., 5.,0.) };
		Polygon building1 = factory.createPolygon(
				factory.createLinearRing(building1Coords), null);
		
		//Create obstruction test object
		FastObstructionTest manager = new FastObstructionTest();
		manager.addGeometry(building1);
		Long deb=System.currentTimeMillis();
		manager.finishPolygonFeeding(new Envelope(new Coordinate(0., 0.,0.),
				new Coordinate(45., 45.,0.)));
		System.out.println("finishPolygonFeeding in "+(System.currentTimeMillis()-deb)+"ms");
		//Run intersection test
		collisionTask(manager);
		//Run wide angle detection
		openAngleTask(manager,building1Coords);
	}
	private void collisionTask(FastObstructionTest manager) throws LayerDelaunayError {

		assertTrue("Intersection test #1 failed",manager.isFreeField(new Coordinate(5,20), new Coordinate(14,30)));
		assertFalse("Intersection test #2 failed",manager.isFreeField(new Coordinate(5,20), new Coordinate(16,31)));

	}
	private void openAngleTask(FastObstructionTest manager,Coordinate[] buildingCoords) throws LayerDelaunayError {
		List<Coordinate> wideangle=manager.getWideAnglePoints(Math.PI * (1 + 1 / 16.0), Math.PI * (2 - (1 / 16.)));
		assertTrue("Too many corners found",wideangle.size()==4);
		for(Coordinate buildingCorner : buildingCoords)
		{
			boolean found=false;
			for(Coordinate widangl : wideangle) {
				double dist=widangl.distance(buildingCorner);
				if(dist>FastObstructionTest.wideAngleTranslationEpsilon-FastObstructionTest.epsilon && dist<FastObstructionTest.wideAngleTranslationEpsilon+FastObstructionTest.epsilon) {
					found=true;
					break;
				}
			}
			assertTrue("Corner at "+buildingCorner+" of building not found !",found);				
		}
	}
}
