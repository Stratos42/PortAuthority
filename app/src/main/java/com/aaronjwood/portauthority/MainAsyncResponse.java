package com.aaronjwood.portauthority;

import java.util.ArrayList;
import java.util.Map;

public interface MainAsyncResponse {

    void processFinish(ArrayList<Map<String, String>> output);

    void processFinish(int output);
}