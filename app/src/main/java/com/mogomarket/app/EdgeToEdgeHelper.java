package com.mogomarket.app;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;

public class EdgeToEdgeHelper {
    public static void enable(ComponentActivity activity) {
        if (activity != null) {
            EdgeToEdge.enable(activity);
        }
    }
}
