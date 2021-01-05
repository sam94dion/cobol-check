package com.neopragma.cobolcheck;

import java.util.*;

public class KeywordExtractor implements TokenExtractor, Constants {

    private Map<String, String> twoWordTokens;
    private StringBuilder buffer;
    private String nextExpectedToken = EMPTY_STRING;
    private boolean openQuote = false;
    private char quoteDelimiter = '"';

    public KeywordExtractor() {
        twoWordTokens = new HashMap<>();
        twoWordTokens.put("TO", "BE");
    }

    @Override
    public List<String> extractTokensFrom(String sourceLine) {
        List<String> tokens = new ArrayList<>();
        buffer = new StringBuilder();
        int tokenOffset = 0;
        sourceLine = sourceLine.trim();
        while (tokenOffset < sourceLine.length()) {
            if (sourceLine.charAt(tokenOffset) == '.') {
                break;
            }
            if (isQuote(sourceLine.charAt(tokenOffset))) {
                char currentChar = sourceLine.charAt(tokenOffset);
                if (openQuote) {
                    if (currentChar == quoteDelimiter) {
                        openQuote = false;
                        buffer.append(currentChar);
                        buffer = addTokenAndClearBuffer(buffer, tokens);
                    } else {
                        buffer.append(currentChar);
                    }
                } else {
                    openQuote = true;
                    quoteDelimiter = currentChar;
                    buffer.append(currentChar);
                }
            } else {
                if (sourceLine.charAt(tokenOffset) == ' ') {
                    if (openQuote) {
                        buffer.append(SPACE);
                    } else {
                        if (twoWordTokens.containsKey(buffer.toString().toUpperCase(Locale.ROOT))) {
                            nextExpectedToken = twoWordTokens.get(buffer.toString().toUpperCase(Locale.ROOT));
                            buffer.append(SPACE);

                            int startOfLookahead = tokenOffset + 1;
                            int endOfLookahead = startOfLookahead + nextExpectedToken.length();
                            if (nextExpectedToken.equalsIgnoreCase(sourceLine.substring(startOfLookahead, endOfLookahead))
                                    && (endOfLookahead >= sourceLine.length()
                                    || sourceLine.charAt(endOfLookahead) == ' ')) {
                                    buffer.append(nextExpectedToken);
                                    tokenOffset += nextExpectedToken.length();
                                    nextExpectedToken = EMPTY_STRING;
                            } else {
                                buffer = addTokenAndClearBuffer(buffer, tokens);
                                nextExpectedToken = EMPTY_STRING;
                            }
                        } else {
                            nextExpectedToken = EMPTY_STRING;
                            if (buffer.length() > 0) {
                                buffer = addTokenAndClearBuffer(buffer, tokens);
                            }
                        }
                    }
                } else{
                    buffer.append(sourceLine.charAt(tokenOffset));
                }
            }
            tokenOffset += 1;
        }
        if (buffer.length() > 0) {
            buffer = addTokenAndClearBuffer(buffer, tokens);
        }
        return tokens;
    }

    private boolean isQuote(char character) {
        return character == '"' || character == '\'';
    }

    private StringBuilder addTokenAndClearBuffer(StringBuilder buffer, List<String> tokens) {
        tokens.add(buffer.toString().trim());
        return new StringBuilder();
    }
}