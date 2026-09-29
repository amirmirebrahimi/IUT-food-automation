package ir.khatman.dksession;

import android.os.Bundle;
import android.os.PowerManager;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity implements SniperEngine.Log {

    private static final int[] MEAL_IDS = {1, 2, 3, 6, 7};
    private static final int[] SELF_IDS = {0, 1, 2, 3};

    private EditText studentId, password;
    private Spinner mealSpinner, selfSpinner;
    private Button startBtn, stopBtn;
    private TextView logView, stateView;
    private ScrollView logScroll;

    private SniperEngine engine;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = new Prefs(this);

        studentId = findViewById(R.id.studentId);
        password = findViewById(R.id.password);
        mealSpinner = findViewById(R.id.mealSpinner);
        selfSpinner = findViewById(R.id.selfSpinner);
        startBtn = findViewById(R.id.startBtn);
        stopBtn = findViewById(R.id.stopBtn);
        logView = findViewById(R.id.logView);
        stateView = findViewById(R.id.stateView);
        logScroll = findViewById(R.id.logScroll);

        logView.setMovementMethod(new ScrollingMovementMethod());

        ArrayAdapter<CharSequence> mealAd = ArrayAdapter.createFromResource(
                this, R.array.meals, R.layout.spinner_item);
        mealAd.setDropDownViewResource(R.layout.spinner_dropdown_item);
        mealSpinner.setAdapter(mealAd);

        ArrayAdapter<CharSequence> selfAd = ArrayAdapter.createFromResource(
                this, R.array.selfs, R.layout.spinner_item);
        selfAd.setDropDownViewResource(R.layout.spinner_dropdown_item);
        selfSpinner.setAdapter(selfAd);

        // restore
        studentId.setText(prefs.getUser());
        password.setText(prefs.getPass());
        mealSpinner.setSelection(safe(prefs.getMeal(), MEAL_IDS.length));
        selfSpinner.setSelection(safe(prefs.getSelf(), SELF_IDS.length));

        startBtn.setOnClickListener(v -> onStart());
        stopBtn.setOnClickListener(v -> onStop());

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sniper:lock");
    }

    private int safe(int i, int max) { return (i < 0 || i >= max) ? 0 : i; }

    @Override
    public void onLog(String line) {
        logView.append(line + "\n");
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void onStart() {
        String u = studentId.getText().toString().trim();
        String p = password.getText().toString();

        if (u.isEmpty() || p.isEmpty()) {
            Toast.makeText(this, "شماره دانشجویی و رمز را وارد کن", Toast.LENGTH_SHORT).show();
            return;
        }

        int mealIdx = mealSpinner.getSelectedItemPosition();
        int selfIdx = selfSpinner.getSelectedItemPosition();
        int mealId = MEAL_IDS[mealIdx];
        String selfName = selfSpinner.getSelectedItem().toString();

        prefs.save(u, p, mealIdx, selfIdx);

        logView.setText("");
        stateView.setText(R.string.running);
        stateView.setTextColor(getResources().getColor(R.color.success, getTheme()));
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);

        try { if (!wakeLock.isHeld()) wakeLock.acquire(); } catch (Exception ignored) {}

        engine = new SniperEngine(this);

        worker = new Thread(() -> {
            engine.run(u, p, mealId, selfName,
                    3500,   // list interval
                    2000,   // buy interval
                    100);   // max buy attempts
            runOnUiThread(this::onFinished);
        }, "sniper");
        worker.start();
    }

    private void onStop() {
        if (engine != null) engine.stop();
        if (worker != null) worker.interrupt();
        onFinished();
    }

    private void onFinished() {
        stateView.setText(R.string.idle);
        stateView.setTextColor(getResources().getColor(R.color.text_dim, getTheme()));
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);
        try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (engine != null) engine.stop();
        try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
    }
}