/*
 * Copyright (c) 2014 Simon Robinson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ac.robinson.ticqr;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.os.AsyncTask;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

class TickBoxImageParserTask extends AsyncTask<Void, Bitmap, ArrayList<PointF>> {

	private static final String TAG = TickBoxImageParserTask.class.getSimpleName();
	private static final boolean DEBUG = false;

	private final Bitmap mBitmap;
	private final float mBoxSize;

	private final TickBoxImageParserCallback mCallback;

	public interface TickBoxImageParserCallback {
		void boxDetectionFailed();

		void boxDetectionSucceeded(ArrayList<PointF> result);
	}

	public TickBoxImageParserTask(Bitmap bitmap, float boxSize, TickBoxImageParserCallback callback) {
		mBitmap = bitmap;
		mBoxSize = boxSize;
		mCallback = callback;
	}

	@Override
	protected ArrayList<PointF> doInBackground(Void... unused) {
		Log.d(TAG, "Searching for tick boxes of " + mBoxSize + " size");

		// we look for *un-ticked* boxes, rather than ticked, as they are uniform in appearance (and hence easier to
		// detect) - they show up as a box within a box
		ArrayList<PointF> centrePoints = new ArrayList<>();
		int minimumOuterBoxArea = (int) Math.round(Math.pow(mBoxSize, 2));
		int maximumOuterBoxArea = (int) Math.round(Math.pow(mBoxSize * 1.35f, 2));
		int minimumInnerBoxArea = (int) Math.round(Math.pow(mBoxSize * 0.5f, 2));

		// image adjustment - blurSize, blurSTDev and adaptiveThresholdSize must not be even numbers
		int blurSize = 9;
		int blurSTDev = 3;
		int adaptiveThresholdSize = Math.round(mBoxSize * 3); // (oddness ensured below)
		int adaptiveThresholdC = 4; // value to add to the mean (can be negative or zero)
		adaptiveThresholdSize = adaptiveThresholdSize % 2 == 0 ? adaptiveThresholdSize + 1 : adaptiveThresholdSize;

		// how similar the recognised polygon must be to its actual contour - lower is more similar
		float outerPolygonSimilarity = 0.045f;
		float innerPolygonSimilarity = 0.075f; // don't require as much accuracy for the inner part of the tick box

		// how large the maximum internal angle can be (e.g., for checking square shape)
		float maxOuterAngleCos = 0.3f;
		float maxInnerAngleCos = 0.4f;

		// use OpenCV to recognise boxes that have a box inside them - i.e. an un-ticked tick box
		// see: http://stackoverflow.com/a/11427501
		// Bitmap newBitmap = mBitmap.copy(Bitmap.Config.RGB_565, true); // not needed
		Mat bitMat = new Mat();
		Utils.bitmapToMat(mBitmap, bitMat);

		// blur and convert to grey
		// alternative (less flexible): Imgproc.medianBlur(bitMat, bitMat, blurSize);
		Imgproc.GaussianBlur(bitMat, bitMat, new Size(blurSize, blurSize), blurSTDev, blurSTDev);
		Imgproc.cvtColor(bitMat, bitMat, Imgproc.COLOR_RGB2GRAY); // need 8uC1 (1 channel, unsigned char) image type

		// perform adaptive thresholding to detect edges
		// alternative (slower): Imgproc.Canny(bitMat, bitMat, 10, 20, 3, false);
		Imgproc.adaptiveThreshold(bitMat, bitMat, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY,
				adaptiveThresholdSize, adaptiveThresholdC);

		// get the contours in the image, and their hierarchy
		Mat hierarchyMat = new Mat();
		List<MatOfPoint> contours = new ArrayList<>();
		Imgproc.findContours(bitMat, contours, hierarchyMat, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);
		if (DEBUG) {
			Imgproc.drawContours(bitMat, contours, -1, new Scalar(30, 255, 255), 1);
		}

		// parse the contours and look for a box containing another box, with similar enough sizes
		int numContours = contours.size();
		ArrayList<Integer> searchedContours = new ArrayList<>();
		Log.d(TAG, "Found " + numContours + " possible tick box areas");
		if (numContours > 0 && !hierarchyMat.empty()) {
			for (int i = 0; i < numContours; i++) {

				// the original detected contour
				MatOfPoint boxPoints = contours.get(i);

				// hierarchy key: 0 = next sibling num, 1 = previous sibling num, 2 = first child num, 3 = parent num
				int childBox = (int) hierarchyMat.get(0, i)[2]; // usually the largest child (as we're doing RETR_TREE)
				if (childBox == -1) { // we only want elements that have children
					continue;
				} else {
					if (searchedContours.contains(childBox)) {
						if (DEBUG) {
							Log.d(TAG, "Ignoring duplicate box at first stage: " + childBox);
						}
						continue;
					} else {
						searchedContours.add(childBox);
					}
				}

				// discard smaller (i.e. noise) outer box areas as soon as possible for speed
				// used to do Imgproc.isContourConvex(outerPoints) later, but the angle check covers this, so no need
				double originalArea = Math.abs(Imgproc.contourArea(boxPoints));
				if (originalArea < minimumOuterBoxArea) {
					// if (DEBUG) {
					// drawPoints(bitMat, boxPoints, new Scalar(255, 255, 255), 1);
					// Log.d(TAG, "Outer box too small");
					// }
					continue;
				}
				if (originalArea > maximumOuterBoxArea) {
					// if (DEBUG) {
					// drawPoints(bitMat, boxPoints, new Scalar(255, 255, 255), 1);
					// Log.d(TAG, "Outer box too big");
					// }
					continue;
				}

				// simplify the contours of the outer box - we want to detect four-sided shapes only
				MatOfPoint2f boxPoints2f = new MatOfPoint2f(boxPoints.toArray()); // Point2f for approxPolyDP
				Imgproc.approxPolyDP(boxPoints2f, boxPoints2f, outerPolygonSimilarity * Imgproc.arcLength(boxPoints2f,
						true), true); // simplify the contour
				if (boxPoints2f.height() != 4) { // height is number of points
					if (DEBUG) {
						// drawPoints(bitMat, new MatOfPoint(boxPoints2f.toArray()), new Scalar(255, 255, 255), 1);
						Log.d(TAG, "Outer box not 4 points");
					}
					continue;
				}

				// check that the simplified outer box is approximately a square, angle-wise
				org.opencv.core.Point[] boxPointsArray = boxPoints2f.toArray();
				double maxCosine = 0;
				for (int j = 0; j < 4; j++) {
					org.opencv.core.Point pL = boxPointsArray[j];
					org.opencv.core.Point pIntersect = boxPointsArray[(j + 1) % 4];
					org.opencv.core.Point pR = boxPointsArray[(j + 2) % 4];
					getLineAngle(pL, pIntersect, pR);
					maxCosine = Math.max(maxCosine, getLineAngle(pL, pIntersect, pR));
				}
				if (maxCosine > maxOuterAngleCos) {
					if (DEBUG) {
						// drawPoints(bitMat, new MatOfPoint(boxPoints2f.toArray()), new Scalar(255, 255, 255), 1);
						Log.d(TAG, "Outer angles not square enough");
					}
					continue;
				}

				// check that the simplified outer box is approximately a square, line length-wise
				double minLine = Double.MAX_VALUE;
				double maxLine = 0;
				for (int p = 1; p < 4; p++) {
					org.opencv.core.Point p1 = boxPointsArray[p - 1];
					org.opencv.core.Point p2 = boxPointsArray[p];
					double xd = p1.x - p2.x;
					double yd = p1.y - p2.y;
					double lineLength = Math.sqrt((xd * xd) + (yd * yd));
					minLine = Math.min(minLine, lineLength);
					maxLine = Math.max(maxLine, lineLength);
				}
				if (maxLine - minLine > minLine) {
					if (DEBUG) {
						// drawPoints(bitMat, new MatOfPoint(boxPoints2f.toArray()), new Scalar(255, 255, 255), 1);
						Log.d(TAG, "Outer lines not square enough");
					}
					continue;
				}

				// draw the outer box if debugging
				if (DEBUG) {
					MatOfPoint debugBoxPoints = new MatOfPoint(boxPointsArray);
					Log.d(TAG, "Potential tick box: " + boxPoints2f.size() + ", " +
							"area: " + Math.abs(Imgproc.contourArea(debugBoxPoints)) + " (min:" +
							minimumOuterBoxArea + ", max:" + maximumOuterBoxArea + ")");
					drawPoints(bitMat, debugBoxPoints, new Scalar(50, 255, 255), 2);
				}

				// loop through the children - they should be in descending size order, but sometimes this is wrong
				boolean wrongBox = false;
				while (true) {
					if (DEBUG) {
						Log.d(TAG, "Looping with box: " + childBox);
					}

					// we've previously tried a child - try the next one
					// key: 0 = next sibling num, 1 = previous sibling num, 2 = first child num, 3 = parent num
					if (wrongBox) {
						childBox = (int) hierarchyMat.get(0, childBox)[0];
						if (childBox == -1) {
							break;
						}
						if (searchedContours.contains(childBox)) {
							if (DEBUG) {
								Log.d(TAG, "Ignoring duplicate box at loop stage: " + childBox);
							}
							break;
						} else {
							searchedContours.add(childBox);
						}
						//noinspection UnusedAssignment
						wrongBox = false;
					}

					// perhaps this is the outer box - check its child has no children itself
					// (removed so tiny children (i.e. noise) don't mean we mis-detect an un-ticked box as ticked)
					// if (hierarchyMat.get(0, childBox)[2] != -1) {
					// continue;
					// }

					// check the size of the child box is large enough
					boxPoints = contours.get(childBox);
					originalArea = Math.abs(Imgproc.contourArea(boxPoints));
					if (originalArea < minimumInnerBoxArea) {
						if (DEBUG) {
							// drawPoints(bitMat, boxPoints, new Scalar(255, 255, 255), 1);
							Log.d(TAG, "Inner box too small");
						}
						wrongBox = true;
						continue;
					}

					// simplify the contours of the inner box - again, we want four-sided shapes only
					boxPoints2f = new MatOfPoint2f(boxPoints.toArray());
					Imgproc.approxPolyDP(boxPoints2f, boxPoints2f, innerPolygonSimilarity * Imgproc.arcLength
							(boxPoints2f, true), true);
					if (boxPoints2f.height() != 4) { // height is number of points
						// if (DEBUG) {
						// drawPoints(bitMat, boxPoints, new Scalar(255, 255, 255), 1);
						// }
						Log.d(TAG, "Inner box fewer than 4 points"); // TODO: allow > 4 for low quality images?
						wrongBox = true;
						continue;
					}

					// check that the simplified inner box is approximately a square, angle-wise
					// higher tolerance because noise means if we get several inners, the box may not be quite square
					boxPointsArray = boxPoints2f.toArray();
					maxCosine = 0;
					for (int j = 0; j < 4; j++) {
						org.opencv.core.Point pL = boxPointsArray[j];
						org.opencv.core.Point pIntersect = boxPointsArray[(j + 1) % 4];
						org.opencv.core.Point pR = boxPointsArray[(j + 2) % 4];
						getLineAngle(pL, pIntersect, pR);
						maxCosine = Math.max(maxCosine, getLineAngle(pL, pIntersect, pR));
					}
					if (maxCosine > maxInnerAngleCos) {
						Log.d(TAG, "Inner angles not square enough");
						wrongBox = true;
						continue;
					}

					// this is probably an inner box - log if debugging
					if (DEBUG) {
						Log.d(TAG, "Un-ticked inner box: " + boxPoints2f.size() + ", " +
								"area: " + Math.abs(Imgproc.contourArea(new MatOfPoint2f(boxPointsArray))) + " (min: "
								+ minimumInnerBoxArea + ")");
					}

					// find the inner box centre
					double centreX = (boxPointsArray[0].x + boxPointsArray[1].x +
							boxPointsArray[2].x + boxPointsArray[3].x) / 4f;
					double centreY = (boxPointsArray[0].y + boxPointsArray[1].y +
							boxPointsArray[2].y + boxPointsArray[3].y) / 4f;

					// draw the inner box if debugging
					if (DEBUG) {
						drawPoints(bitMat, new MatOfPoint(boxPointsArray), new Scalar(255, 255, 255), 1);
						Imgproc.circle(bitMat, new org.opencv.core.Point(centreX, centreY), 3, new Scalar(255, 255, 255));
					}

					// add to the list of boxes to check
					centrePoints.add(new PointF((float) centreX, (float) centreY));
					break;
				}
			}
		}

		Log.d(TAG, "Found " + centrePoints.size() + " un-ticked boxes");
		return centrePoints;
	}

	private void drawPoints(Mat bitMat, MatOfPoint boxPoints, Scalar colour, int width) {
		List<MatOfPoint> simplifiedOuterBox = new ArrayList<>();
		simplifiedOuterBox.add(boxPoints);
		Imgproc.drawContours(bitMat, simplifiedOuterBox, -1, colour, width);
	}

	private double getLineAngle(org.opencv.core.Point pL, org.opencv.core.Point pIntersect, org.opencv.core.Point pR) {
		double dx21 = pL.x - pIntersect.x;
		double dx31 = pR.x - pIntersect.x;
		double dy21 = pL.y - pIntersect.y;
		double dy31 = pR.y - pIntersect.y;
		double m12 = Math.sqrt(dx21 * dx21 + dy21 * dy21);
		double m13 = Math.sqrt(dx31 * dx31 + dy31 * dy31);
		return Math.abs((dx21 * dx31 + dy21 * dy31) / (m12 * m13));
	}

	@Override
	protected void onPostExecute(ArrayList<PointF> result) {
		if (result == null) {
			mCallback.boxDetectionFailed();
		} else {
			mCallback.boxDetectionSucceeded(result);
		}
	}
}
