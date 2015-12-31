package com.kz.grbl;

import java.util.Comparator;

public class MyPickablePointZMidXYComparator implements Comparator<MyPickablePoint> {

	public MyPickablePointZMidXYComparator() {
	}

	@Override
	public int compare(MyPickablePoint p1, MyPickablePoint p2) {
		// TODO Auto-generated method stub

		if (p1.xyz.z > p2.xyz.z)
			return -1;
		else if (p1.xyz.z < p2.xyz.z)
			return 1;
		else {
			if (Math.abs(p1.xyz.x) > Math.abs(p2.xyz.x))
				return 1;
			else if (Math.abs(p1.xyz.x) < Math.abs(p2.xyz.x))
				return -1;
			else
				if (p1.xyz.y > p2.xyz.y)
					return -1;
				else if (p1.xyz.y < p2.xyz.y)
					return 1;
		}		
		return 0;
	}
}
