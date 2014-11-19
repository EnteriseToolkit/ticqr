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

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.JsonHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import org.apache.http.Header;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opencv.android.OpenCVLoader;

import java.util.ArrayList;

import ac.robinson.dualqrscanner.CodeParameters;
import ac.robinson.dualqrscanner.DecoderActivity;
import ac.robinson.dualqrscanner.ImageParameters;
import ac.robinson.dualqrscanner.QRImageParser;
import ac.robinson.dualqrscanner.ViewfinderView;
import ac.robinson.dualqrscanner.camera.CameraUtilities;

public class TicQRActivity extends DecoderActivity {

	private static final String TAG = TicQRActivity.class.getSimpleName();

	private static final String SERVER_URL = "http://enterise.info/codemaker/pages.php";

	private ImageView mImageView;

	private Bitmap mBitmap;
	private ImageParameters mImageParameters;
	private CodeParameters mCodeParameters;

	private float mBoxSize;

	private String mDestinationEmail;
	private ArrayList<TickBoxHolder> mServerTickBoxes = new ArrayList<TickBoxHolder>();
	private ArrayList<PointF> mImageTickBoxes = new ArrayList<PointF>();

	private boolean mBoxesLoaded = false;
	private boolean mImageParsed = false;

	private String mEmailContents;

	static {
		// see: http://stackoverflow.com/a/12661981
		if (!OpenCVLoader.initDebug()) {
			// TODO: handle initialisation error
			throw new RuntimeException();
		}
	}

	private class TickBoxHolder {
		public final PointF location;
		public final String description;
		public final int quantity;
		public boolean ticked;
		public boolean foundOnImage;

		public PointF gridPosition;

		public TickBoxHolder(PointF location, String description, int quantity) {
			this.location = location;
			this.description = description;
			this.quantity = quantity;
		}

		public void setGridPosition(PointF position) {
			gridPosition = position;
		}
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		setContentView(R.layout.main);

		setViews(R.id.viewfinder_view, R.id.preview_view, R.id.image_view);
		setResizeImageToView(true); // a lower-quality image

		mImageView = (ImageView) findViewById(R.id.image_view);

		if (!CameraUtilities.getIsCameraAvailable(getPackageManager())) {
			Toast.makeText(TicQRActivity.this, getString(R.string.hint_no_camera), Toast.LENGTH_SHORT).show();
			finish();
			return;
		}

		// set up action bar
		ActionBar actionBar = getSupportActionBar();
		actionBar.setTitle(R.string.title_activity_capture);
		actionBar.setDisplayShowTitleEnabled(true);

		int resultPointColour = getResources().getColor(R.color.accent);
		((ViewfinderView) findViewById(R.id.viewfinder_view)).setResultPointColour(resultPointColour);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.menu, menu);
		if (TextUtils.isEmpty(mEmailContents)) {
			// don't show the send button when there is no email to send
			menu.findItem(R.id.action_send_order).setVisible(false);
		}
		if (mBitmap == null) {
			menu.findItem(R.id.action_rescan).setVisible(false);
		}
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.action_send_order:
				sendOrder();
				return true;

			case R.id.action_rescan:
				// reset our configuration and set up for rescanning
				mBitmap = null;
				mDestinationEmail = null;
				mServerTickBoxes.clear();
				mImageTickBoxes.clear();

				mBoxesLoaded = false;
				mImageParsed = false;
				mEmailContents = null;

				mImageView.setVisibility(View.INVISIBLE); // must be invisible (not gone) as we need its dimensions
				RelativeLayout highlightHolder = (RelativeLayout) findViewById(R.id.tick_highlight_holder);
				highlightHolder.removeAllViews();

				getSupportActionBar().setTitle(R.string.title_activity_capture);
				supportInvalidateOptionsMenu();
				requestScanResume();
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override
	protected void onDecodeCompleted() {
		// Toast.makeText(TicQRActivity.this, "Decode completed; now taking picture", Toast.LENGTH_SHORT).show();
	}

	@Override
	protected void onPageIdFound(String id) {
		// Toast.makeText(TicQRActivity.this, "Page ID found", Toast.LENGTH_SHORT).show();

		AsyncHttpClient client = new AsyncHttpClient();
		RequestParams params = new RequestParams("lookup", id);
		client.get(SERVER_URL, params, new JsonHttpResponseHandler() {
			@Override
			public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
				Log.d(TAG, response.toString());
				try {
					if ("ok".equals(response.getString("status"))) {
						mDestinationEmail = response.isNull("destination") ? null : response.getString("destination");

						JSONArray boxes = response.getJSONArray("tickBoxes");
						if (boxes != null && !boxes.isNull(0)) {
							for (int i = 0; i < boxes.length(); i++) {
								JSONObject jsonBox = boxes.getJSONObject(i);

								TickBoxHolder box = new TickBoxHolder(new PointF(jsonBox.getInt("x"),
										jsonBox.getInt("y")), jsonBox.getString("description"),
										jsonBox.getInt("quantity"));

								box.ticked = true; // first we assume all boxes are ticked
								box.foundOnImage = false; // (but not yet found on the image)
								mServerTickBoxes.add(box);
							}
						}

						mBoxesLoaded = true;
						if (mImageParsed) {
							verifyBoxes();
						}
					}
				} catch (JSONException e) {
					Toast.makeText(TicQRActivity.this, getString(R.string.hint_json_error), Toast.LENGTH_SHORT).show();
				}
			}

			@Override
			public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject errorResponse) {
				Toast.makeText(TicQRActivity.this, getString(R.string.hint_connection_error),
						Toast.LENGTH_SHORT).show();
			}
		});
	}

	@Override
	protected void onPictureError() {
		// note: an automatic rescan is started whenever this occurs, so this is mainly designed for, e.g.,
		// counting a large number of errors and prompting the user to reposition the camera
		// Toast.makeText(TicQRActivity.this, "Picture error", Toast.LENGTH_SHORT).show();
	}

	@Override
	protected void onPictureCompleted(Bitmap parsedBitmap, ImageParameters imageParameters,
	                                  CodeParameters codeParameters) {
		// Toast.makeText(TicQRActivity.this, "Picture completed", Toast.LENGTH_SHORT).show();

		mImageView.setImageBitmap(parsedBitmap);
		mImageView.setVisibility(View.VISIBLE);
		mBitmap = parsedBitmap;

		mImageParameters = imageParameters;
		mCodeParameters = codeParameters;

		// TODO: dependent on the smallest QR code size (e.g., those with more control points will make this fail)
		mBoxSize = (mCodeParameters.mPointSpacing / 15) * 7;

		TickBoxImageParserTask parserTask = new TickBoxImageParserTask(parsedBitmap, mBoxSize,
				new TickBoxImageParserTask.TickBoxImageParserCallback() {
			@Override
			public void boxDetectionFailed() {
				TicQRActivity.this.boxDetectionFailed();
			}

			@Override
			public void boxDetectionSucceeded(ArrayList<PointF> result) {
				TicQRActivity.this.boxDetectionSucceeded(result);
			}
		});

		findViewById(R.id.parse_progress).setVisibility(View.VISIBLE);
		parserTask.execute();
	}

	private void boxDetectionFailed() {
		Toast.makeText(TicQRActivity.this, getString(R.string.hint_box_detection_failed), Toast.LENGTH_SHORT).show();
	}

	private void boxDetectionSucceeded(ArrayList<PointF> result) {
		mImageTickBoxes = result;

		mImageParsed = true;
		if (mBoxesLoaded) {
			verifyBoxes();
		}
	}

	private void verifyBoxes() {
		if (mServerTickBoxes == null) {
			return; // TODO
		}

		// scans the list comparing with the actual tick box positions (some could be outside the image)
		int maximumBoxDistance = Math.round(mBoxSize * 0.75f);
		int maximumQRCodeDistance = Math.round(mBoxSize * 0.4f);
		int maximumQRCodeDistanceSquared = maximumQRCodeDistance * maximumQRCodeDistance;

		Log.d(TAG, "Searching for codes at max distance: " + maximumBoxDistance + " (QR dist: " +
				maximumQRCodeDistance + ")");

		// update the server boxes with their position on the image
		for (TickBoxHolder tickBox : mServerTickBoxes) {
			tickBox.setGridPosition(QRImageParser.getGridPosition(mImageParameters, tickBox.location));
		}

		// first pass - match ticked boxes on the image with ticked boxes from the server
		for (PointF p : mImageTickBoxes) {

			// check that we're not too close to a QR code
			boolean qrBox = false;
			for (int q = 0, qn = Math.min(mCodeParameters.mIdPoints.length, mCodeParameters.mAlignmentPoints.length);
			     q < qn; q++) {
				PointF idP = mCodeParameters.mIdPoints[q];
				PointF alignP = mCodeParameters.mAlignmentPoints[q];
				double pX1 = (idP.x - p.x);
				double pY1 = (idP.y - p.y);
				double pX2 = (alignP.x - p.x);
				double pY2 = (alignP.y - p.y);
				if (((pX1 * pX1) + (pY1 * pY1)) < maximumQRCodeDistanceSquared || ((pX2 * pX2) + (pY2 * pY2)) <
						maximumQRCodeDistanceSquared) {
					qrBox = true;
					break;
				}
			}
			if (qrBox) {
				// Log.d(TAG, "Box seems to be a QR point - ignoring");
				continue;
			}

			float minDistance = Float.MAX_VALUE;
			TickBoxHolder assignedBox = null;
			for (TickBoxHolder tickBox : mServerTickBoxes) {
				if (tickBox.foundOnImage) {
					continue;
				}

				PointF pos = tickBox.gridPosition;
				float boxDistance = (float) Math.sqrt(Math.pow(p.x - pos.x, 2) + Math.pow(p.y - pos.y, 2));
				if (boxDistance < maximumBoxDistance && boxDistance < minDistance) {
					assignedBox = tickBox;
					minDistance = boxDistance;
				}
			}
			if (assignedBox != null) {
				Log.d(TAG, "Found closest box (" + assignedBox.description + ") at " + minDistance + " distance");
				assignedBox.foundOnImage = true;
				assignedBox.ticked = false;
			} else {
				Log.d(TAG, "Couldn't find actual box for detected box at " + minDistance + " distance");
			}
		}

		// second pass - un-tick any boxes that are still marked as ticked, but are actually outside the image,
		// then add an animated tick box on those that remain
		RelativeLayout highlightHolder = (RelativeLayout) findViewById(R.id.tick_highlight_holder);
		Animation pulse = AnimationUtils.loadAnimation(this, R.anim.pulse);
		int tickIcon = R.drawable.ic_highlight_tick;
		Drawable tickDrawable = getResources().getDrawable(tickIcon);
		int iconWidth = tickDrawable.getIntrinsicWidth();
		int iconHeight = tickDrawable.getIntrinsicHeight();

		boolean tickedBoxes = false;
		StringBuilder itemsBuilder = new StringBuilder();
		for (TickBoxHolder tickBox : mServerTickBoxes) {
			if (tickBox.ticked) {
				PointF gridPosition = tickBox.gridPosition;
				try {
					if (mBitmap.getPixel((int) gridPosition.x, (int) gridPosition.y) == Color.TRANSPARENT) {
						tickBox.ticked = false;
						Log.d(TAG, "Un-ticking box outside the image (" + tickBox.description + " " +
								"at " + gridPosition.x + "," + gridPosition.y + ")");
					}
				} catch (IllegalArgumentException e) {
					tickBox.ticked = false;
					Log.d(TAG, "Un-ticking box with invalid image coordinate (" + tickBox.description + ") at " +
							gridPosition.x + "," + gridPosition.y);
				}

				if (tickBox.ticked) {
					Log.d(TAG, "Ticked box (" + tickBox.description + ") found at " + gridPosition.x + "," +
							"" + gridPosition.y);

					tickedBoxes = true;
					itemsBuilder.append(getString(R.string.email_item, tickBox.quantity, tickBox.description));

					ImageView tickHighlight = new ImageView(TicQRActivity.this);
					tickHighlight.setImageResource(tickIcon);
					RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams
							.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
					layoutParams.leftMargin = mImageView.getLeft() + Math.round(gridPosition.x) - (iconWidth / 2);
					layoutParams.topMargin = mImageView.getTop() + Math.round(gridPosition.y) - (iconHeight / 2);

					highlightHolder.addView(tickHighlight, layoutParams);
					tickHighlight.startAnimation(pulse);
				}
			}
		}

		findViewById(R.id.parse_progress).setVisibility(View.GONE);

		getSupportActionBar().setTitle(R.string.title_activity_order);
		if (tickedBoxes) {
			mEmailContents = itemsBuilder.toString();
			supportInvalidateOptionsMenu(); // to show the place order button
			Toast.makeText(TicQRActivity.this, getString(R.string.hint_send_order), Toast.LENGTH_SHORT).show();
		} else {
			Toast.makeText(TicQRActivity.this, getString(R.string.hint_no_boxes_found), Toast.LENGTH_SHORT).show();
		}
	}

	private void sendOrder() {
		try {
			Intent emailIntent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto",
					TextUtils.isEmpty(mDestinationEmail) ? "" : mDestinationEmail, null));
			emailIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.email_subject));
			emailIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.email_body, mEmailContents));
			startActivity(Intent.createChooser(emailIntent, getString(R.string.email_prompt)));
		} catch (ActivityNotFoundException e) {
			// copy to clipboard instead if no email client found
			String clipboardText = getString(R.string.email_backup_sender, TextUtils.isEmpty(mDestinationEmail) ? "" :
					mDestinationEmail, getString(R.string.email_body, mEmailContents));

			// see: http://stackoverflow.com/a/11012443
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
				@SuppressLint("ServiceCast") @SuppressWarnings("deprecation") android.text.ClipboardManager clipboard
						= (android.text.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
				clipboard.setText(clipboardText);
			} else {
				android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService
						(Context.CLIPBOARD_SERVICE);
				android.content.ClipData clip = android.content.ClipData.newPlainText(getString(R.string
						.email_subject), clipboardText);
				clipboard.setPrimaryClip(clip);
			}

			Toast.makeText(TicQRActivity.this, getString(R.string.hint_no_email_client), Toast.LENGTH_LONG).show();
		}
	}
}
