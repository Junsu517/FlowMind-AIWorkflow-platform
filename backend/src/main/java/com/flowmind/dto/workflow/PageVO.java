package com.flowmind.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PageVO<T> {

    private List<T> items;
    private long total;
    private long page;
    private long size;
}
