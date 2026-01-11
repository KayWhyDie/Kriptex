package com.ivor.kriptex;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import android.Manifest;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;
import com.ivor.kriptex.adapters.ContactsAdapter;
import com.ivor.kriptex.db.Contact;
import com.ivor.kriptex.db.Database;
import com.ivor.kriptex.service.CoatexHostService;
import com.ivor.kriptex.tor.Client;
import com.ivor.kriptex.tor.Notifier;
import com.ivor.kriptex.tor.Server;
import com.ivor.kriptex.tor.Tor;
import com.ivor.kriptex.utils.Settings;
import com.ivor.kriptex.utils.Util;
import com.ivor.kriptex.view.TorStatusView;
import com.theartofdev.edmodo.cropper.CropImage;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.realm.Realm;
import io.realm.RealmResults;
import io.realm.Sort;

public class MainActivity extends AppCompatActivity {

    public static final int REQUEST_QR = 12;
    private static final int PR_CAMERA = 2001;
    private static final String TAG = MainActivity.class.getName();

    private String mCurrentQrPhotoPath;

    private Tor mTor;

    private void send() {
        Client.getInstance(this).startSendPendingFriends();
    }

    private RecyclerView mRVContacts;
    public ContactsAdapter mContactsAdapter;
    private RealmResults<Contact> mContacts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean use_dark_mode = Settings.getPrefs(this).getBoolean("use_dark_mode", false);

        if (use_dark_mode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }

        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mTor = Tor.getInstance(this);

        ContextCompat.startForegroundService(this, new Intent(this, CoatexHostService.class));

        findViewById(R.id.btnRequests).setOnClickListener(view -> startActivity(new Intent(this, RequestActivity.class)));

        mRVContacts = findViewById(R.id.rcvwContacts);

        mContacts = Realm.getDefaultInstance().where(Contact.class)
                .equalTo("incoming", 0)
                .findAll()
                .sort("lastMessageTime", Sort.DESCENDING);

        mRVContacts.setLayoutManager(new LinearLayoutManager(this));
        mContactsAdapter = new ContactsAdapter(mContacts, this, false);
        mRVContacts.setAdapter(mContactsAdapter);
        mRVContacts.setHasFixedSize(true);
        mRVContacts.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));

        updateNoDataView();

        mContacts.addChangeListener((contacts1, changeSet) -> updateNoDataView());

        Realm.getDefaultInstance().where(Contact.class)
                .notEqualTo("incoming", 0).findAll().addChangeListener((contacts, changeSet) -> setRequestsUpdate());

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(view -> {
            View v = getLayoutInflater().inflate(R.layout.dialog_connect, null);

            final AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    //.setTitle(R.string.add_contact)
                    .setView(v)
                    .show();

            ((TextView) v.findViewById(R.id.id)).setText(Tor.getInstance(MainActivity.this).getID());
            v.findViewById(R.id.qr_show).setOnClickListener(v1 -> {
                dialog.cancel();
                showQR();
            });
            v.findViewById(R.id.qr_scan).setOnClickListener(v12 -> {
                dialog.cancel();
                scanQR();
            });
            v.findViewById(R.id.enter_id).setOnClickListener(v13 -> {
                dialog.cancel();
                addContact();
            });
            v.findViewById(R.id.copy_my_id).setOnClickListener(v13 -> {
                dialog.cancel();
                Util.setClipboard(this, mTor.getID());
                snack("ID copied to clipboard" + mTor.getID());
            });
            v.findViewById(R.id.share_id).setOnClickListener(v14 -> {
                dialog.cancel();
//                        inviteFriend();
            });

        });

        checkBatteryOptimization();
//        checkAutoStartOption();
    }

    private void updateNoDataView() {
        findViewById(R.id.txtNoContacts).setVisibility(mContactsAdapter.getItemCount() > 0 ? View.INVISIBLE : View.VISIBLE);

        setRequestsUpdate();
    }

    private void refreshContactsUi() {
        if (mContactsAdapter != null) {
            mContactsAdapter.notifyDataSetChanged();
        }
        updateNoDataView();
    }

    private void checkAutoStartOption() {
        String manufacturer = android.os.Build.MANUFACTURER;
        try {
            Intent intent = new Intent();
            if ("xiaomi".equalsIgnoreCase(manufacturer)) {
                intent.setComponent(new ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            } else if ("oppo".equalsIgnoreCase(manufacturer)) {
                intent.setComponent(new ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
            } else if ("vivo".equalsIgnoreCase(manufacturer)) {
                intent.setComponent(new ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
            } else if ("Letv".equalsIgnoreCase(manufacturer)) {
                intent.setComponent(new ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"));
            } else if ("Honor".equalsIgnoreCase(manufacturer)) {
                intent.setComponent(new ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"));
            }

            List<ResolveInfo> list = getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (list.size() > 0) {
                startActivity(intent);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent();
            String packageName = getPackageName();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.setAction(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
                startActivity(intent);
            }
        }
    }

    void scanQR() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    PR_CAMERA);
            return;
        }
        launchEmbeddedQrScanner();
    }

    private void launchEmbeddedQrScanner() {
        // Use an in-app live scanner to avoid OEM camera quirks and heavy bitmap decoding/ANRs.
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt(getString(R.string.scan_qr));
        integrator.setBeepEnabled(false);
        integrator.setBarcodeImageEnabled(false);
        integrator.setOrientationLocked(false);
        integrator.initiateScan();
    }

    // Legacy fallback: capture a full-resolution still image and decode it.
    // Kept for devices where the embedded scanner activity cannot start.
    private void launchQrCamera() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, "No camera app available", Toast.LENGTH_SHORT).show();
            return;
        }

        // Prefer a full-resolution image (thumbnail extras are often too low-res for QR decoding).
        File photoFile;
        try {
            photoFile = File.createTempFile("qr_", ".jpg", getCacheDir());
            mCurrentQrPhotoPath = photoFile.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "Unable to create temp file for QR scan", e);
            photoFile = null;
            mCurrentQrPhotoPath = null;
        }

        if (photoFile != null) {
            Uri photoUri = FileProvider.getUriForFile(
                    this,
                    getApplicationContext().getPackageName() + ".provider",
                    photoFile
            );
            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            takePictureIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }

        try {
            startActivityForResult(takePictureIntent, REQUEST_QR);
        } catch (SecurityException se) {
            // Some OEM camera apps enforce that the caller holds CAMERA.
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    PR_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PR_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchEmbeddedQrScanner();
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Handle embedded scanner results first (it may return RESULT_CANCELED on back press).
        IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scanResult != null) {
            String contents = scanResult.getContents();
            if (contents == null) {
                // Cancelled.
                return;
            }
            handleQrPayload(contents);
            return;
        }

        if (resultCode != RESULT_OK) return;

        if (requestCode == REQUEST_QR) {
            // Legacy still-image decode: do the work off the UI thread to avoid ANRs (seen on MIUI).
            final String photoPath = mCurrentQrPhotoPath;
            final Intent resultData = data;
            new Thread(() -> {
                String payload = null;
                try {
                    if (photoPath != null) {
                        File f = new File(photoPath);
                        if (f.exists() && f.length() > 0) {
                            payload = decodeQrPayloadFromFile(photoPath);
                        }
                    }

                    if (payload == null && resultData != null && resultData.getExtras() != null) {
                        Object thumb = resultData.getExtras().get("data");
                        if (thumb instanceof Bitmap) {
                            Bitmap bitmap = (Bitmap) thumb;
                            Log.i(TAG, "QR using thumbnail bitmap w=" + bitmap.getWidth() + " h=" + bitmap.getHeight());
                            payload = decodeQrPayload(bitmap);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Legacy QR decode failed", e);
                }

                final String finalPayload = payload;
                runOnUiThread(() -> handleQrPayload(finalPayload));
            }, "qr-decode").start();
        } else if (requestCode == CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE) {
            CropImage.ActivityResult result = CropImage.getActivityResult(data);
            if (resultCode == RESULT_OK) {
                Uri resultUri = result.getUri();
//                setDP(resultUri);
                Database.getInstance(this).put("dp", resultUri.getPath());
                Log.d(TAG, "onActivityResult: " + resultUri.getPath());
            } else if (resultCode == CropImage.CROP_IMAGE_ACTIVITY_RESULT_ERROR_CODE) {
            }
        }
    }

    private void handleQrPayload(String payload) {
        try {
            if (payload == null) {
                snack(getString(R.string.invalid_qr_code));
                return;
            }

            ParsedQr parsed = parseCoatexQr(payload);
            if (parsed == null) {
                snack(getString(R.string.invalid_qr_code));
                return;
            }

            if (Contact.hasContact(this, parsed.id)) {
                snack(getString(R.string.contact_already_added));
                return;
            }

            addContact(parsed.id, parsed.name);
        } catch (Exception ex) {
            snack(getString(R.string.invalid_qr_code));
            ex.printStackTrace();
        }
    }

    private String decodeQrPayload(Bitmap bitmap) {
        if (bitmap == null) return null;

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));

        MultiFormatReader reader = new MultiFormatReader();
        reader.setHints(hints);

        // Try full bitmap first, then crops (helps with busy backgrounds / off-center QR).
        // Also retry with 90/180/270 rotations because some OEM camera apps produce images
        // where EXIF orientation is missing or unreadable.
        List<Bitmap> candidates = buildQrDecodeCandidates(bitmap);
        try {
            for (Bitmap candidate : candidates) {
                String text = tryDecodeQrBitmapWithRotations(candidate, reader);
                if (text != null) {
                    Log.i(TAG, "QR decoded: " + text);
                    return text;
                }
            }
        } finally {
            // Recycle temporary crop bitmaps we created.
            for (Bitmap candidate : candidates) {
                if (candidate != null && candidate != bitmap) {
                    try {
                        candidate.recycle();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        Log.i(TAG, "QR decode failed");
        return null;
    }

    private String decodeQrPayloadFromFile(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || f.length() <= 0) return null;

            Log.i(TAG, "QR photo path=" + path + " size=" + f.length());

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int w = Math.max(1, bounds.outWidth);
            int h = Math.max(1, bounds.outHeight);
            int max = Math.max(w, h);

            int sample = 1;
            while (max / sample > 2400) sample *= 2;
            if (sample < 1) sample = 1;

            // Try progressively higher resolutions: sample, sample/2, ..., 1
            for (int s = sample; s >= 1; s = (s == 1 ? 0 : Math.max(1, s / 2))) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = s;
                Bitmap bmp = null;
                try {
                    bmp = BitmapFactory.decodeFile(path, opts);
                    if (bmp == null) {
                        Log.w(TAG, "QR decodeFile returned null (sample=" + s + ")");
                        continue;
                    }
                    Log.i(TAG, "QR bitmap decoded w=" + bmp.getWidth() + " h=" + bmp.getHeight() + " sample=" + s);

                    // EXIF may be broken on some OEMs, but if we can read it, it still helps.
                    int rotate = Util.getCameraPhotoOrientation(path);
                    if (rotate != 0) {
                        Log.i(TAG, "QR EXIF rotate=" + rotate);
                        try {
                            Matrix m = new Matrix();
                            m.postRotate(rotate);
                            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                            if (rotated != null && rotated != bmp) {
                                bmp.recycle();
                                bmp = rotated;
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "QR EXIF rotation failed", e);
                        }
                    }

                    String payload = decodeQrPayload(bmp);
                    if (payload != null) return payload;
                } finally {
                    if (bmp != null) {
                        try {
                            bmp.recycle();
                        } catch (Exception ignored) {
                        }
                    }
                }

                if (s == 1) break;
            }

            return null;
        } catch (Exception e) {
            Log.w(TAG, "QR decode from file failed", e);
            return null;
        }
    }

    private List<Bitmap> buildQrDecodeCandidates(Bitmap bitmap) {
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int min = Math.min(w, h);
            if (min <= 0) return Collections.singletonList(bitmap);

            // Center crops at 90% and 75% of min dimension.
            int crop90 = (int) (min * 0.90f);
            int crop75 = (int) (min * 0.75f);
            Bitmap c90 = safeCenterCrop(bitmap, crop90);
            Bitmap c75 = safeCenterCrop(bitmap, crop75);

            // If QR is not centered, also try 75% crops around each quadrant.
            Bitmap q1 = safeCropAround(bitmap, w / 4, h / 4, crop75);
            Bitmap q2 = safeCropAround(bitmap, (w * 3) / 4, h / 4, crop75);
            Bitmap q3 = safeCropAround(bitmap, w / 4, (h * 3) / 4, crop75);
            Bitmap q4 = safeCropAround(bitmap, (w * 3) / 4, (h * 3) / 4, crop75);

            ArrayList<Bitmap> out = new ArrayList<>();
            out.add(bitmap);
            if (c90 != null) out.add(c90);
            if (c75 != null) out.add(c75);
            if (q1 != null) out.add(q1);
            if (q2 != null) out.add(q2);
            if (q3 != null) out.add(q3);
            if (q4 != null) out.add(q4);
            return out;
        } catch (Exception e) {
            return Collections.singletonList(bitmap);
        }
    }

    private Bitmap safeCenterCrop(Bitmap bitmap, int cropSize) {
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int size = Math.max(1, Math.min(cropSize, Math.min(w, h)));
            int left = Math.max(0, (w - size) / 2);
            int top = Math.max(0, (h - size) / 2);
            if (left + size > w || top + size > h) return null;
            return Bitmap.createBitmap(bitmap, left, top, size, size);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Bitmap safeCropAround(Bitmap bitmap, int centerX, int centerY, int cropSize) {
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int size = Math.max(1, Math.min(cropSize, Math.min(w, h)));

            int left = centerX - (size / 2);
            int top = centerY - (size / 2);
            if (left < 0) left = 0;
            if (top < 0) top = 0;
            if (left + size > w) left = w - size;
            if (top + size > h) top = h - size;
            if (left < 0 || top < 0) return null;

            return Bitmap.createBitmap(bitmap, left, top, size, size);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String tryDecodeQrBitmapWithRotations(Bitmap bitmap, MultiFormatReader reader) {
        if (bitmap == null) return null;

        // 0°
        String text = tryDecodeQrBitmap(bitmap, reader);
        if (text != null) return text;

        // 90° / 180° / 270°
        for (int deg : new int[]{90, 180, 270}) {
            Bitmap rotated = null;
            try {
                rotated = rotateBitmap(bitmap, deg);
                if (rotated == null) continue;
                text = tryDecodeQrBitmap(rotated, reader);
                if (text != null) {
                    Log.i(TAG, "QR decoded after rotate=" + deg);
                    return text;
                }
            } catch (Exception ignored) {
            } finally {
                if (rotated != null && rotated != bitmap) {
                    try {
                        rotated.recycle();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        return null;
    }

    private Bitmap rotateBitmap(Bitmap bitmap, int degrees) {
        int d = ((degrees % 360) + 360) % 360;
        if (d == 0) return bitmap;
        Matrix m = new Matrix();
        m.postRotate(d);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
    }

    private String tryDecodeQrBitmap(Bitmap bitmap, MultiFormatReader reader) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return null;

        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);

        // Try common combos: Hybrid + normal/inverted, then GlobalHistogram + normal/inverted.
        String text;

        text = tryDecodeBinaryBitmap(new BinaryBitmap(new HybridBinarizer(source)), reader);
        if (text != null) return text;

        text = tryDecodeBinaryBitmap(new BinaryBitmap(new HybridBinarizer(source.invert())), reader);
        if (text != null) return text;

        text = tryDecodeBinaryBitmap(new BinaryBitmap(new GlobalHistogramBinarizer(source)), reader);
        if (text != null) return text;

        return tryDecodeBinaryBitmap(new BinaryBitmap(new GlobalHistogramBinarizer(source.invert())), reader);
    }

    private String tryDecodeBinaryBitmap(BinaryBitmap bitmap, MultiFormatReader reader) {
        try {
            Result result = reader.decodeWithState(bitmap);
            return result == null ? null : result.getText();
        } catch (Exception ignored) {
            return null;
        } finally {
            try {
                reader.reset();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class ParsedQr {
        final String id;
        final String name;

        ParsedQr(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private ParsedQr parseCoatexQr(String raw) {
        if (raw == null) return null;

        String normalized = raw.trim();
        if (normalized.isEmpty()) return null;
        normalized = normalized.replace('\uFEFF', ' ');

        String lowered = normalized.toLowerCase(Locale.US);

        // Primary expected format: "Kriptex <id> <name...>"
        String[] tokens = normalized.trim().split("\\s+", 3);
        if (tokens.length >= 2) {
            String app = tokens[0].trim();
            if (app.equalsIgnoreCase("Kriptex")) {
                String id = tokens[1].trim().toLowerCase(Locale.US);
                if (id.endsWith(".onion")) id = id.substring(0, id.length() - ".onion".length());
                id = id.replaceAll("[^a-z2-7]", "");
                if (id.length() == 56 || id.length() == 16) {
                    String name = tokens.length >= 3 ? tokens[2].trim() : "";
                    return new ParsedQr(id, name);
                }
            }
        }

        // Fallback: find an onion id anywhere in the payload (prefer v3).
        Matcher m = Pattern.compile("([a-z2-7]{56})").matcher(lowered);
        if (m.find()) {
            return new ParsedQr(m.group(1), "");
        }

        m = Pattern.compile("([a-z2-7]{16})").matcher(lowered);
        if (m.find()) {
            return new ParsedQr(m.group(1), "");
        }

        return null;
    }

    void showQR() {
        String name = Database.getInstance(this).getName();
        if (name == null) name = "";
        String txt = "Kriptex " + mTor.getID() + " " + name;
        QRCode qr;
        try {
            qr = Encoder.encode(txt, ErrorCorrectionLevel.M);
        } catch (Exception ex) {
            throw new Error(ex);
        }
        ByteMatrix mat = qr.getMatrix();
        int width = mat.getWidth();
        int height = mat.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                pixels[offset + x] = mat.get(x, y) != 0 ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        bitmap = Bitmap.createScaledBitmap(bitmap, bitmap.getWidth() * 8, bitmap.getHeight() * 8, false);
        ImageView view = new ImageView(this);
        view.setImageBitmap(bitmap);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());
        view.setPadding(pad, pad, pad, pad);
        Rect displayRectangle = new Rect();
        Window window = getWindow();
        window.getDecorView().getWindowVisibleDisplayFrame(displayRectangle);
        int s = (int) (Math.min(displayRectangle.width(), displayRectangle.height()) * 0.9);
        view.setMinimumWidth(s);
        view.setMinimumHeight(s);
        new AlertDialog.Builder(this)
                .setView(view)
                .show();
    }

    private void changeName() {
        final FrameLayout view = new FrameLayout(this);
        final EditText editText = new EditText(this);
        editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        editText.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editText.setSingleLine();
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        view.addView(editText);
        int padding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());

        final Database db = Database.getInstance(this);

        view.setPadding(padding, padding, padding, padding);
        editText.setText(db.getName());
        new AlertDialog.Builder(this)
                .setTitle(R.string.change_alias)
                .setView(view)
                .setPositiveButton(R.string.apply, (dialog, which) -> {
                    db.setName(editText.getText().toString().trim());
                    update();
                    snack(getString(R.string.alias_changed));
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                }).show();
    }

    private void update() {
        runOnUiThread(() -> {
            Database db = Database.getInstance(MainActivity.this);
            getSupportActionBar().setTitle(db.getName().trim().isEmpty() ? "Anonymous" : db.getName());
            getSupportActionBar().setSubtitle(mTor.getID());
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        Tor.getInstance(this).addListener(mTorListener);
        Server.getInstance(this).addListener(mServerListener);
        update();
        send();

        Notifier.getInstance(this).onResumeActivity();

        ((TorStatusView) findViewById(R.id.torStatusView)).update();

        ContextCompat.startForegroundService(this, new Intent(this, CoatexHostService.class));

        refreshContactsUi();
    }

    private Server.Listener mServerListener = () -> update();

    private Tor.Listener mTorListener = () -> {
        update();
        send();
    };

    @Override
    protected void onPause() {
        Notifier.getInstance(this).onPauseActivity();
        Tor.getInstance(this).removeListener(mTorListener);
        Server.getInstance(this).removeListener(mServerListener);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mContacts.removeAllChangeListeners();
    }

    void snack(String s) {
        Snackbar.make(findViewById(R.id.content), s, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);

        MenuItem mi = menu.findItem(R.id.action_search);
        if (mi != null) {
            SearchView searchView = (SearchView) mi.getActionView();
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    if (mContactsAdapter != null) {
                        mContactsAdapter.filter(query);
                    }
                    return false;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    if (mContactsAdapter != null) {
                        mContactsAdapter.filter(newText);
                    }
                    return false;
                }
            });
            searchView.setOnCloseListener(() -> {
                if (mContactsAdapter != null) {
                    mContactsAdapter.filter(null);
                }
                Log.d(TAG, "onCreateOptionsMenu: Closing Search View");
                return false;
            });

        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    void addContact() {
        addContact("", "");
    }

    void addContact(String id, String alias) {

        final View view = getLayoutInflater().inflate(R.layout.dialog_add, null);
        final EditText idEd = view.findViewById(R.id.add_id);
        idEd.setText(id);
        final EditText aliasEd = view.findViewById(R.id.add_alias);
        aliasEd.setText(alias);
        final EditText aliasDescription = view.findViewById(R.id.add_description);
        AlertDialog alertDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.add_contact)
                .setView(view)
                .setPositiveButton(R.string.ok, (dialog, which) -> {

                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                }).create();

        alertDialog.setOnShowListener(dialogInterface -> {

            Button button = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            button.setOnClickListener(view1 -> {

                String id1 = idEd.getText().toString().trim();
                id1 = id1.toLowerCase(Locale.US);
                if (id1.endsWith(".onion")) id1 = id1.substring(0, id1.length() - ".onion".length());
                id1 = id1.replaceAll("[^a-z2-7]", "");
                if (id1.length() != 56) {
                    snack(getString(R.string.invalid_id));
                    idEd.setError(getString(R.string.invalid_id));
                    return;
                }
                if (id1.equals(mTor.getID())) {
                    snack(getString(R.string.cannot_add_self));
                    idEd.setError(getString(R.string.cannot_add_self));
                    return;
                }
                if (!Contact.addContact(MainActivity.this,
                        id1,
                        aliasEd.getText().toString().trim(),
                        aliasDescription.getText().toString().trim(),
                        null,
                        true,
                        false)) {
                    snack(getString(R.string.contact_already_present));
                    Toast.makeText(this, getString(R.string.contact_already_added), Toast.LENGTH_SHORT).show();
                    return;
                }
                snack(getString(R.string.contact_added));
                send();

                refreshContactsUi();

                alertDialog.dismiss();
            });
        });

        alertDialog.show();
    }

    public void setRequestsUpdate() {
        int incoming;
        Realm realm = Realm.getDefaultInstance();
        try {
            incoming = (int) realm.where(Contact.class).notEqualTo("incoming", 0).count();
        } finally {
            realm.close();
        }
        Button button = findViewById(R.id.btnRequests);
        if (incoming > 0) {
            button.setVisibility(View.VISIBLE);
            button.setText(getResources().getQuantityString(R.plurals.new_requests, incoming, incoming));
        } else {
            // Keep Requests accessible even when there are no pending requests.
            button.setVisibility(View.VISIBLE);
            button.setText(R.string.requests);
        }
    }
}
