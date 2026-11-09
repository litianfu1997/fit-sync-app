package com.fitsync.cloud.connector;

import java.util.List;

public record ActivityPage(List<RemoteActivity> items, String nextCursor, boolean hasMore) {
  public ActivityPage {
    items = List.copyOf(items);
  }
}
