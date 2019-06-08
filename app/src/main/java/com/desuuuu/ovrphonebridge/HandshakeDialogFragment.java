package com.desuuuu.ovrphonebridge;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

public class HandshakeDialogFragment extends DialogFragment {
    private HandshakeDialogListener mListener;
    private String mIdentifier;
    private CheckBox mWhitelist;

    HandshakeDialogFragment(HandshakeDialogListener listener, String identifier) {
        mListener = listener;
        mIdentifier = identifier;
    }

    @NonNull
    @Override
    @SuppressLint("InflateParams")
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LayoutInflater inflater = requireActivity().getLayoutInflater();

        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());

        View contentView = inflater.inflate(R.layout.dialog_handshake, null);

        TextView contentText = contentView.findViewById(R.id.handshake_prompt_content_identifier);

        contentText.setText(mIdentifier);

        mWhitelist = contentView.findViewById(R.id.handshake_prompt_whitelist);

        return builder
                .setTitle(R.string.handshake_prompt_title)
                .setView(contentView)
                .setPositiveButton(R.string.handshake_prompt_continue, (dialog, id) -> mListener.onHandshakeDialogContinue(mIdentifier, mWhitelist.isChecked()))
                .setNegativeButton(R.string.handshake_prompt_abort, (dialog, id) -> mListener.onHandshakeDialogAbort())
                .create();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        mListener.onHandshakeDialogCancel();
    }

    public interface HandshakeDialogListener {
        void onHandshakeDialogContinue(String identifier, boolean whitelist);
        void onHandshakeDialogAbort();
        void onHandshakeDialogCancel();
    }
}
