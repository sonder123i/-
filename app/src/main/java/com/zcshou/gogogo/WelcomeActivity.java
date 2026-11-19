package com.zcshou.gogogo;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.method.MovementMethod;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.zcshou.gogogo.run.ui.home.RunHomeActivity;
import com.zcshou.gogogo.run.ui.theme.ThemeHelper;
import com.zcshou.utils.GoUtils;

public class WelcomeActivity extends AppCompatActivity {
    private static SharedPreferences preferences;
    private static final String KEY_ACCEPT_AGREEMENT = "KEY_ACCEPT_AGREEMENT";
    private static final String KEY_ACCEPT_PRIVACY = "KEY_ACCEPT_PRIVACY";
    private static final String KEY_ONBOARDED = "KEY_ONBOARDED";

    private CheckBox checkBox;
    private Boolean mAgreement = false;
    private Boolean mPrivacy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.INSTANCE.apply(this);
        super.onCreate(savedInstanceState);

        // 已同意过协议的老用户：不再重复展示欢迎页
        preferences = getSharedPreferences(KEY_ACCEPT_AGREEMENT, MODE_PRIVATE);
        boolean onboarded = preferences.getBoolean(KEY_ONBOARDED, false)
                || (preferences.getBoolean(KEY_ACCEPT_AGREEMENT, false)
                && preferences.getBoolean(KEY_ACCEPT_PRIVACY, false));
        if (onboarded) {
            goHome();
            return;
        }

        setContentView(R.layout.activity_welcome);

        // 生成默认参数的值（一定要尽可能早的调用，因为后续有些界面可能需要使用参数）
        PreferenceManager.setDefaultValues(this, R.xml.preferences_main, false);

        TextView startBtn = findViewById(R.id.startButton);
        startBtn.setOnClickListener(v -> startMainActivity());

        checkAgreementAndPrivacy();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void startMainActivity() {
        if (!checkBox.isChecked()) {
            // 不勾选也能继续：直接弹一次同意确认，避免用户卡在这一页
            showConsentConfirm();
            return;
        }
        acceptAndEnter();
    }

    private void showConsentConfirm() {
        new AlertDialog.Builder(this)
                .setTitle("用户协议与隐私政策")
                .setMessage("为提供模拟位置服务，需要你同意《用户协议》和《隐私政策》。\n\n"
                        + "我们零读取通讯录与相册、零获取设备串号，轨迹仅存本机。")
                .setPositiveButton("同意并进入", (d, w) -> {
                    checkBox.setChecked(true);
                    acceptAndEnter();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void acceptAndEnter() {
        // 勾选即视为已阅读并同意，无需逐个点开协议
        preferences.edit()
                .putBoolean(KEY_ACCEPT_AGREEMENT, true)
                .putBoolean(KEY_ACCEPT_PRIVACY, true)
                .putBoolean(KEY_ONBOARDED, true)
                .apply();
        goHome();
    }

    /** 冷启动不申请任何系统权限，直接进入干净的主界面 */
    private void goHome() {
        Intent intent = new Intent(WelcomeActivity.this, RunHomeActivity.class);
        startActivity(intent);
        finish();
        overridePendingTransition(0, 0);
    }

    private void doAcceptation() {
        if (mAgreement && mPrivacy) {
            checkBox.setChecked(true);
        } else {
            checkBox.setChecked(false);
        }
        //实例化Editor对象
        SharedPreferences.Editor editor = preferences.edit();
        //存入数据
        editor.putBoolean(KEY_ACCEPT_AGREEMENT, mAgreement);
        editor.putBoolean(KEY_ACCEPT_PRIVACY, mPrivacy);
        //提交修改
        editor.apply();
    }

    private void showAgreementDialog() {
        final AlertDialog alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.show();
        alertDialog.setCancelable(false);
        Window window = alertDialog.getWindow();
        if (window != null) {
            window.setContentView(R.layout.user_agreement);
            window.setGravity(Gravity.CENTER);
            window.setWindowAnimations(R.style.DialogAnimFadeInFadeOut);

            TextView tvContent = window.findViewById(R.id.tv_content);
            Button tvCancel = window.findViewById(R.id.tv_cancel);
            Button tvAgree = window.findViewById(R.id.tv_agree);
            SpannableStringBuilder ssb = new SpannableStringBuilder();
            ssb.append(getResources().getString(R.string.app_agreement_content));
            tvContent.setMovementMethod(LinkMovementMethod.getInstance());
            tvContent.setText(ssb, TextView.BufferType.SPANNABLE);

            tvCancel.setOnClickListener(v -> {
                mAgreement = false;

                doAcceptation();

                alertDialog.cancel();
            });

            tvAgree.setOnClickListener(v -> {
                mAgreement = true;

                doAcceptation();

                alertDialog.cancel();
            });
        }
    }

    private void showPrivacyDialog() {
        final AlertDialog alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.show();
        alertDialog.setCancelable(false);
        Window window = alertDialog.getWindow();
        if (window != null) {
            window.setContentView(R.layout.user_privacy);
            window.setGravity(Gravity.CENTER);
            window.setWindowAnimations(R.style.DialogAnimFadeInFadeOut);

            TextView tvContent = window.findViewById(R.id.tv_content);
            Button tvCancel = window.findViewById(R.id.tv_cancel);
            Button tvAgree = window.findViewById(R.id.tv_agree);
            SpannableStringBuilder ssb = new SpannableStringBuilder();
            ssb.append(getResources().getString(R.string.app_privacy_content));
            tvContent.setMovementMethod(LinkMovementMethod.getInstance());
            tvContent.setText(ssb, TextView.BufferType.SPANNABLE);

            tvCancel.setOnClickListener(v -> {
                mPrivacy = false;

                doAcceptation();

                alertDialog.cancel();
            });

            tvAgree.setOnClickListener(v -> {
                mPrivacy = true;

                doAcceptation();

                alertDialog.cancel();
            });
        }
    }

    private void checkAgreementAndPrivacy() {
        checkBox = findViewById(R.id.check_agreement);
        checkBox.setChecked(false);

        // 协议链接独立成行，避免链接的触摸处理吞掉勾选框点击
        TextView links = findViewById(R.id.tvAgreementLinks);
        links.setText(getAgreementLinks());
        links.setMovementMethod(LinkMovementMethod.getInstance());
        links.setHighlightColor(0x00000000);
    }

    @NonNull
    private CharSequence getAgreementLinks() {
        String text = "《用户协议》 · 《隐私政策》";
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        int agreementEnd = text.indexOf("》") + 1;
        builder.setSpan(
                new ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View widget) {
                        showAgreementDialog();
                    }

                    @Override
                    public void updateDrawState(TextPaint ds) {
                        ds.setColor(getResources().getColor(R.color.pf_volt, getTheme()));
                        ds.setUnderlineText(false);
                    }
                },
                0, agreementEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        int privacyStart = text.indexOf("《", agreementEnd);
        builder.setSpan(
                new ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View widget) {
                        showPrivacyDialog();
                    }

                    @Override
                    public void updateDrawState(TextPaint ds) {
                        ds.setColor(getResources().getColor(R.color.pf_volt, getTheme()));
                        ds.setUnderlineText(false);
                    }
                },
                privacyStart, text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        return builder;
    }
}
