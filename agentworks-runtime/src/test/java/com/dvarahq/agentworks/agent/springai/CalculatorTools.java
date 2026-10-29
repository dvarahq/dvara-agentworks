package com.dvarahq.agentworks.agent.springai;

import org.springframework.ai.tool.annotation.Tool;

final class CalculatorTools {

    @Tool(name = "add", description = "Adds two integers")
    int add(int left, int right) {
        return left + right;
    }
}
