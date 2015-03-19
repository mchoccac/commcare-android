package org.commcare.android.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.commcare.android.framework.UiElement;
import org.commcare.dalvik.R;

/**
 * Created by dancluna on 3/18/15.
 */
public class SquareButtonWithNotification extends RelativeLayout {
    @UiElement(R.id.square_button_text)
    SquareButtonWithText buttonWithText;

    @UiElement(R.id.button_subtext)
    TextView subText;

    //region View parameters

    Drawable backgroundImg;
    int backgroundColorButton = android.R.drawable.btn_default;
    int backgroundColorNotification = R.drawable.bubble;
    String subtitleButton = "";
    String textNotification = "";

    //endregion

    //region Constructors

    public SquareButtonWithNotification(Context context, AttributeSet attrs) {
        super(context, attrs);

        setUI(context, attrs);
    }

    public SquareButtonWithNotification(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        setUI(context, attrs);
    }

    //endregion

    //region Private methods

    private void setUI(Context context, AttributeSet attrs) {
        View view = inflate(context, R.layout.square_button_notification, this);
        buttonWithText = (SquareButtonWithText)view.findViewById(R.id.square_button_text);
        subText = (TextView)view.findViewById(R.id.button_subtext);

        if(attrs != null) {
            TypedArray typedArray = context.getTheme().obtainStyledAttributes(attrs, R.styleable.SquareButtonWithNotification, 0, 0);

            backgroundImg = typedArray.getDrawable(R.styleable.SquareButtonWithNotification_sbn_img);
            backgroundColorButton = typedArray.getResourceId(R.styleable.SquareButtonWithNotification_backgroundcolorButton, backgroundColorButton);
            backgroundColorNotification = typedArray.getResourceId(R.styleable.SquareButtonWithNotification_backgroundcolorNotification, backgroundColorNotification);
            subtitleButton = typedArray.getString(R.styleable.SquareButtonWithNotification_sbn_subtitle);
            textNotification = typedArray.getString(R.styleable.SquareButtonWithNotification_notificationText);

            typedArray.recycle();

            buttonWithText.setColor(backgroundColorButton);
            buttonWithText.setImage(backgroundImg);
            buttonWithText.setText(subtitleButton);
            if (textNotification != null && textNotification.length() != 0) {
                subText.setVisibility(VISIBLE);
                subText.setText(textNotification);
                subText.setBackgroundResource(backgroundColorNotification);
            } else {
                subText.setVisibility(INVISIBLE);
            }
        }
    }

    //endregion
}
