package com.liveshield.app.setup;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.liveshield.app.R;
import com.liveshield.transport.destination.StreamDestination;

/** Private, session-only destination setup for demo or eligible TikTok external broadcasting. */
public final class StreamDestinationFragment extends Fragment {
    /** Takes ownership of a destination that the host must close on replacement/session end. */
    public interface Listener {
        void onStreamDestinationConfigured(StreamDestination destination);
    }

    private Listener listener;

    public StreamDestinationFragment() {
        super(R.layout.fragment_stream_destination);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (!(context instanceof Listener)) {
            throw new IllegalStateException("Stream destination host must implement Listener");
        }
        listener = (Listener) context;
        if (!(requireActivity() instanceof SetupActivity setupActivity)
                || !setupActivity.isDebugScreenCaptureAllowed()) {
            requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        RadioGroup choices = view.findViewById(R.id.destination_kind);
        EditText endpoint = view.findViewById(R.id.external_stream_endpoint);
        EditText secret = view.findViewById(R.id.external_stream_secret);
        TextView eligibility = view.findViewById(R.id.external_stream_eligibility);
        TextView status = view.findViewById(R.id.destination_private_status);
        Button configure = view.findViewById(R.id.configure_stream_destination);
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setSaveEnabled(false);
        secret.setFreezesText(false);

        choices.setOnCheckedChangeListener((ignored, checkedId) -> {
            boolean external = checkedId == R.id.destination_tiktok_external;
            endpoint.setVisibility(external ? View.VISIBLE : View.GONE);
            secret.setVisibility(external ? View.VISIBLE : View.GONE);
            eligibility.setVisibility(external ? View.VISIBLE : View.GONE);
            status.setText(external
                    ? R.string.destination_status_external_not_configured
                    : R.string.destination_status_demo_not_configured);
        });
        configure.setOnClickListener(ignored -> configure(
                choices.getCheckedRadioButtonId(), endpoint, secret, status));
        choices.check(R.id.destination_local_demo);
    }

    @Override
    public void onDestroyView() {
        EditText secret = getView() == null
                ? null : getView().findViewById(R.id.external_stream_secret);
        if (secret != null) {
            secret.getText().clear();
        }
        super.onDestroyView();
    }

    @Override
    public void onDetach() {
        listener = null;
        super.onDetach();
    }

    private void configure(
            int selectedKind,
            EditText endpoint,
            EditText secret,
            TextView status) {
        StreamDestination destination = null;
        try {
            if (selectedKind == R.id.destination_local_demo) {
                destination = DestinationForm.localDemo();
            } else if (selectedKind == R.id.destination_tiktok_external) {
                destination = DestinationForm.external(
                        endpoint.getText().toString(), secret.getText());
            } else {
                throw new IllegalArgumentException("Choose a destination type");
            }
            listener.onStreamDestinationConfigured(destination);
            destination = null;
            secret.getText().clear();
            status.setText(R.string.destination_status_configured_session_only);
        } catch (DestinationForm.ValidationException failure) {
            status.setText(failure.error() == DestinationForm.ValidationError.SECRET_REQUIRED
                    ? R.string.destination_status_secret_required
                    : R.string.destination_status_endpoint_invalid);
        } finally {
            if (destination != null) {
                destination.close();
            }
        }
    }
}
