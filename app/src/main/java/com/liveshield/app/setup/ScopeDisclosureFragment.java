package com.liveshield.app.setup;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.liveshield.app.R;

/** Static, payload-free disclosure that gates all camera and session setup. */
public final class ScopeDisclosureFragment extends Fragment {
    /** Host callback invoked only after the creator explicitly acknowledges the disclosure. */
    public interface Listener {
        void onScopeDisclosureAccepted();
    }

    private Listener listener;

    public ScopeDisclosureFragment() {
        super(R.layout.fragment_scope_disclosure);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (!(context instanceof Listener)) {
            throw new IllegalStateException("Scope disclosure host must implement Listener");
        }
        listener = (Listener) context;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        view.findViewById(R.id.acknowledge_scope_disclosure).setOnClickListener(ignored -> {
            View button = view.findViewById(R.id.acknowledge_scope_disclosure);
            button.setEnabled(false);
            listener.onScopeDisclosureAccepted();
        });
    }

    @Override
    public void onDetach() {
        listener = null;
        super.onDetach();
    }
}
