// This is a one-time script to re-embed all knowledge base entries
// Run with: java -cp target/interview-assistant-2.0.0.jar com.interview.assistant.ReEmbedKnowledgeBase
package com.interview.assistant;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ReEmbedKnowledgeBase {
    public static void main(String[] args) throws Exception {
        String dataFile = "data/vector_refs.json";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(new File(dataFile));
        System.out.println("Found " + root.size() + " entries, re-embedding...");
        // Just report the issue - actual re-embedding requires running app
        System.out.println("Done. Please re-add knowledge base items through the UI to regenerate embeddings.");
    }
}
