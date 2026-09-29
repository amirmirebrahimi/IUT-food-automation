package ir.khatman.dksession;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    private static final String NAME = "sniper_prefs";
    private static final String K_USER = "user";
    private static final String K_PASS = "pass";
    private static final String K_MEAL = "meal";
    private static final String K_SELF = "self";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public String getUser() { return sp.getString(K_USER, ""); }
    public String getPass() { return sp.getString(K_PASS, ""); }
    public int getMeal() { return sp.getInt(K_MEAL, 1); }
    public int getSelf() { return sp.getInt(K_SELF, 0); }

    public void save(String user, String pass, int meal, int self) {
        sp.edit()
          .putString(K_USER, user)
          .putString(K_PASS, pass)
          .putInt(K_MEAL, meal)
          .putInt(K_SELF, self)
          .apply();
    }
}