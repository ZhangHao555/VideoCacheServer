package com.ahao.serverstudy.cache;

import java.io.File;

public interface ListFile {
    File consume();

    void server(File file);
}
