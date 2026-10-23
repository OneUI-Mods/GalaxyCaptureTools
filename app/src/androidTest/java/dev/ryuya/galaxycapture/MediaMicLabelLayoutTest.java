package dev.ryuya.galaxycapture;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.ryuya.galaxycapture.hook.Reflect;
import org.junit.Test;

/** Measures Samsung's real control and font at several window widths and accessibility sizes. */
public class MediaMicLabelLayoutTest {
  @Test
  public void nativeLabelPreservesWordsAndUsesOneLineWhenItFits() {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    instrumentation.runOnMainSync(
        () -> {
          try {
            Context target = instrumentation.getTargetContext();
            Context host =
                target.createPackageContext(
                    "com.samsung.android.app.smartcapture",
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            Class<?> nativeChoice =
                host.getClassLoader()
                    .loadClass(
                        "com.samsung.android.app.smartcapture.screenrecorder.view.EnhancedRadioButton");
            for (float scale : new float[] {.85f, 1f, 1.3f, 1.5f, 2f}) {
              Configuration config = new Configuration(host.getResources().getConfiguration());
              config.fontScale = scale;
              Context sized = host.createConfigurationContext(config);
              for (int dp : new int[] {280, 360, 600}) {
                ConstraintLayout row = new ConstraintLayout(target);
                View[] choices = new View[3];
                TextView label = null;
                for (int i = 0; i < choices.length; i++) {
                  View choice =
                      (View) nativeChoice.getConstructor(Context.class).newInstance(sized);
                  choices[i] = choice;
                  choice.setId(View.generateViewId());
                  TextView text = (TextView) Reflect.getObjectField(choice, "textView");
                  text.setText(new String[] {"なし", "メディア", "メディアとマイク"}[i]);
                  text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                  if (i == 2) label = text;
                }
                for (int i = 0; i < choices.length; i++) {
                  var p = new ConstraintLayout.LayoutParams(0, -2);
                  p.topToTop = 0;
                  p.horizontalWeight = 1;
                  p.leftMargin = p.rightMargin = px(sized, 4);
                  if (i == 0) p.leftToLeft = 0;
                  else p.leftToRight = choices[i - 1].getId();
                  if (i == 2) p.rightToRight = 0;
                  else p.rightToLeft = choices[i + 1].getId();
                  row.addView(choices[i], p);
                }
                MediaMicLabel wrapper = new MediaMicLabel(label, choices[2]);
                wrapper.update("メディアとマイク", true);
                int width = px(sized, dp);
                for (int pass = 0; pass < 8; pass++) {
                  row.measure(
                      View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                      View.MeasureSpec.makeMeasureSpec(px(sized, 1000), View.MeasureSpec.AT_MOST));
                  row.layout(0, 0, width, row.getMeasuredHeight());
                  wrapper.onGlobalLayout();
                }
                String context = "fontScale=" + scale + "; width=" + dp;
                var layout = label.getLayout();
                assertNotNull(context, layout);
                assertTrue(context, layout.getLineCount() <= 2);
                for (int line = 0; line < layout.getLineCount(); line++)
                  assertEquals(context, 0, layout.getEllipsisCount(line));
                if (layout.getLineCount() == 2) {
                  String first =
                      label.getText().subSequence(0, layout.getLineEnd(0)).toString().trim();
                  assertEquals(context, "メディアと", first);
                  assertEquals(
                      context,
                      "マイク",
                      label
                          .getText()
                          .subSequence(layout.getLineStart(1), layout.getLineEnd(1))
                          .toString());
                } else assertEquals(context, "メディアとマイク", label.getText().toString());
                if (dp == 600) assertEquals(context, 1, layout.getLineCount());
                wrapper.update("マイクのみ", true);
                assertEquals(context, "マイクのみ", label.getText().toString());
                assertEquals(context, 0, choices[2].getLayoutParams().width);
                wrapper.update("メディアとマイク", false);
                assertEquals(context, "メディアとマイク", label.getText().toString());
              }
            }
          } catch (ReflectiveOperationException
              | android.content.pm.PackageManager.NameNotFoundException e) {
            throw new AssertionError(e);
          }
        });
  }

  private static int px(Context c, int dp) {
    return Math.round(dp * c.getResources().getDisplayMetrics().density);
  }
}
