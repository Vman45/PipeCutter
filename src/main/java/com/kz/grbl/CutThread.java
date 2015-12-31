package com.kz.grbl;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.SwingWorker;

import org.jzy3d.colors.Color;
import org.jzy3d.maths.BoundingBox3d;
import org.jzy3d.maths.Coord3d;
import org.jzy3d.plot3d.primitives.Point;

public class CutThread extends SwingWorker<String, Object> {
	public static int delay = 100;
	public static float cutterYRange = 7;
	private static long longDelay = 1000;
	private MyPickablePoint point;
	double sumAngle = 0;
	float topZ;

	ArrayList<MyPickablePoint> lastPoints;
	ArrayList<MyPickablePoint> alAlreadyAddedPoints;

	private boolean wholePipe = false;
	private MyPickablePoint startPoint = null;

//  http://www.pirate4x4.com/forum/11214232-post17.html
//	For cutting 19.05mm with your Powermax1650 (this info is in your Powermax 1650 operators manual) Use 100 Amp consumables, set Amps to 100, cut speed to 660 mm/min (you can go up to 1168.4 mm/min, 
//  660 mm/min will provide better edge quality), set pierce delay to 1.5 seconds, pierce height must be at 5mm to 6.35 mm, set cut height to 3.175 mm 
//	(adjust arc voltage during the cut to maintain 3.175mm torch to work distance...voltage should be roughly 161 volts depending on calibration of your torch height control).
//	On 19mm pay attention to pierce height....one pierce too close, or with too short of a pierce delay- you will destroy the shield and nozzle at this power level.
//	Use the FineCut consumables on everything under 4.76 mm for best quality...as above, pay attention to pierce height and cut height and you will be very satisfied with the results.
//	Best regards, Jim Colt	
	
	public CutThread(boolean wholePipe) {
		ArrayList<MyPickablePoint> sortedList = new ArrayList(SurfaceDemo.instance.utils.points.values());
		Collections.sort(sortedList, new MyPickablePointZYXComparator());
		topZ = sortedList.get(0).getZ();
		this.wholePipe = wholePipe;
		this.startPoint = point;
		this.alAlreadyAddedPoints = new ArrayList<MyPickablePoint>();
	}

	public CutThread(boolean wholePipe, MyPickablePoint point) {
		this(wholePipe);
		this.startPoint = point;
	}

	public void cut() throws InterruptedException {
		int prevInventorEdge = 0;
		ArrayList<MyPickablePoint> sortedList = new ArrayList(SurfaceDemo.instance.utils.points.values());
		Collections.sort(sortedList, new MyPickablePointYComparator());

		//SurfaceDemo.instance.utils.establishNeighbourPoints();
		MyPickablePoint lastOuterPoint = sortedList.get(sortedList.size() - 1);
		lastPoints = SurfaceDemo.instance.utils.findAllConnectedPoints(lastOuterPoint, new ArrayList<MyPickablePoint>());

		double mminY = sortedList.get(0).xyz.y;
		double mmaxY = sortedList.get(sortedList.size() - 1).xyz.y;

		float currentY = (float) mminY;
		alAlreadyAddedPoints = new ArrayList<MyPickablePoint>();
		float minY = 0;
		float maxY = 0;
		int rotationDirection = 1;
		while (currentY - cutterYRange / 2 < mmaxY) {
			minY = currentY - cutterYRange / 2;
			maxY = currentY + cutterYRange / 2;

			System.out.println(minY + " - " + maxY);
			// sumAngle = 0;
			// SurfaceDemo.instance.utils.rotatePoints(sumAngle,true);
			//SurfaceDemo.instance.angleTxt = "0.0";
			cutSegment(minY, maxY, true, rotationDirection);
			double angle = Double.valueOf(SurfaceDemo.instance.angleTxt).doubleValue();
			if (angle >0)
				rotationDirection = -1;
			else
				rotationDirection = 1;
			currentY = currentY + cutterYRange;
		}
		// sumAngle = 0;
		// SurfaceDemo.instance.utils.rotatePoints(-sumAngle,false);
		cutSegment(minY, maxY, false, rotationDirection);
	}

	private void cutSegment(float minY, double maxY, boolean withoutLastPoints, int rotationDirection) {
		System.out.println("Cutting segment " + minY + " " + maxY);
		for (int i = 0; i < 4; i++) {
			ArrayList<MyPickablePoint> pointsToCut = new ArrayList<MyPickablePoint>();
			for (MyPickablePoint p : SurfaceDemo.instance.utils.points.values()) {
				if (p.xyz.y > minY && p.xyz.y <= maxY && Math.abs(p.getZ() - topZ) < 0.1) {
					{
						if (withoutLastPoints) {
							if (!listContainsPoint(p, lastPoints)) {
								if (!listContainsPoint(p, alAlreadyAddedPoints)) {
									pointsToCut.add(p);
									p.setColor(Color.MAGENTA);
								}
							}
						} else {
							if (!listContainsPoint(p, alAlreadyAddedPoints)) {
								pointsToCut.add(p);
								p.setColor(Color.MAGENTA);
							}
						}
					}
				}
			}
			boolean hasBeenCutting = false;
			Collections.sort(pointsToCut, new MyPickablePointMidXComparator());
			if (pointsToCut.size() > 0) {
				for (MyPickablePoint myPoint : pointsToCut) {
					if (!listContainsPoint(myPoint, alAlreadyAddedPoints) && Math.abs(myPoint.getZ() - topZ) < 0.1) {
						SurfaceDemo.instance.moveAbove(myPoint);
						double angle = folowThePath(myPoint, this.alAlreadyAddedPoints);
						hasBeenCutting = true;
					}
				}
			}
			if (hasBeenCutting) {
				double diagonal = (topZ*2*1.41/2);
				MyPickablePoint newPoint = new MyPickablePoint(-100000, new Coord3d(SurfaceDemo.instance.cylinderPoint.xyz.x,
						SurfaceDemo.instance.cylinderPoint.xyz.y, diagonal+5),Color.BLACK,0.4f,-200000);
				SurfaceDemo.instance.move(newPoint,false);
			}

			double angle = rotationDirection * 90.0d;
			SurfaceDemo.instance.utils.rotatePoints(angle, true);
			sumAngle = Float.valueOf(SurfaceDemo.instance.angleTxt); // sumAngle +
																																// angle;
			System.out.print("Angle: " + sumAngle);
			System.out.println();
		}
	}

	public double folowThePath(MyPickablePoint myPoint, ArrayList<MyPickablePoint> alAlreadyAddedPoints) {

		MyPickablePoint tempPoint = myPoint;
		MyPickablePoint prevPoint = myPoint;
		prevPoint = tempPoint;
		boolean shouldBreak = false;
		while (!shouldBreak) {
			SurfaceDemo.instance.move(tempPoint,true);
			
			if (tempPoint != null) {
				tempPoint.setColor(Color.GREEN);
				alAlreadyAddedPoints.add(tempPoint);
			}
			tempPoint = SurfaceDemo.instance.utils.findConnectedPoint(tempPoint, alAlreadyAddedPoints);
			if (tempPoint == null) {
				shouldBreak = true;
				tempPoint = myPoint;
			}

			double angleDelta = rotation(prevPoint, tempPoint);
			// System.out.println(prevPoint.id + " " + tempPoint.id + " " +
			// angleDelta);
			prevPoint = tempPoint;
		}
		SurfaceDemo.instance.move(tempPoint,true);
		prevPoint = tempPoint;
		tempPoint = SurfaceDemo.instance.utils.findConnectedPoint(tempPoint, alAlreadyAddedPoints);
		if (tempPoint != null) {
			double angle = rotation(prevPoint, tempPoint);
			return angle;
		}
		return 0.0d;

	}

	private double rotation(MyPickablePoint prevPoint, MyPickablePoint tempPoint) {

		double angleDeltaDeg = 0;
		if (tempPoint != null && !tempPoint.equals(prevPoint)) {
			double angleDelta = Math.atan2(tempPoint.xyz.z - prevPoint.xyz.z, prevPoint.xyz.x - tempPoint.xyz.x);
			if (Math.abs(angleDelta - Math.PI) < Utils.Math_E)
				angleDelta = 0;
			if (Math.abs(angleDelta + Math.PI) < Utils.Math_E)
				angleDelta = 0;

			angleDeltaDeg = (angleDelta) * 180 / Math.PI;

			if (Math.abs(angleDeltaDeg) < Utils.rotationAngleMin)
				angleDeltaDeg = 0;
			if (Math.abs(180 - angleDeltaDeg) < Utils.rotationAngleMin)
				angleDeltaDeg = 0;

			if (angleDeltaDeg > -180 && angleDeltaDeg < -90)
				angleDeltaDeg = -(180 + angleDeltaDeg);
			else if (angleDeltaDeg > -90 && angleDeltaDeg < 0) {
				angleDeltaDeg = -angleDeltaDeg;
			}

			if (angleDeltaDeg != 0) {
				SurfaceDemo.instance.utils.rotatePoints(angleDeltaDeg, false);
				sumAngle = sumAngle + angleDeltaDeg;
				// System.out.print("\tSumAngle=" + sumAngle + " (delta=" +
				// angleDeltaDeg + ")");
				// System.out.println("");

			}

			// try {
			// Thread.sleep(100);
			// } catch (InterruptedException e) {
			// // TODO Auto-generated catch block
			// e.printStackTrace();
			// }
		}
		return angleDeltaDeg;
	}

	public boolean listContainsPoint(MyPickablePoint p, List<MyPickablePoint> list) {
		for (MyPickablePoint myPickablePoint : list) {
			if (myPickablePoint.id == p.id)
				return true;

		}
		return false;
	}

	@Override
	protected String doInBackground() throws Exception {

		File myFile = new File("prog.gcode");
		System.out.println("File " + myFile.getName() + " deleted?" + myFile.delete());
		if (this.wholePipe)
			cut();
		else
			this.folowThePath(this.startPoint, this.alAlreadyAddedPoints);
		return "Done";
	}

	@Override
	protected void done() {
		try {
			System.out.println("Done.");
		} catch (Exception ignore) {
		}
	}

}