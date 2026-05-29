package org.privaterecord;

import android.text.Editable;
import android.text.TextWatcher;

class SimpleTextWatcher implements TextWatcher {
    private final Runnable onTextChanged;

    SimpleTextWatcher(Runnable onTextChanged) {
        this.onTextChanged = onTextChanged;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        onTextChanged.run();
    }

    @Override
    public void afterTextChanged(Editable s) {
    }
}
