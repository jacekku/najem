package pl.najem.mt940;

import java.util.List;

/**
 * One :20:…:62F: statement block.
 *
 * <p>A statement carries exactly one currency — MT940 states it on the balance fields, not per
 * line — so transactions in two currencies are two statements, never one.
 */
public record Mt940Statement(String account, String statementNumber, String currency, List<Mt940Line> lines) {

    public Mt940Statement {
        lines = List.copyOf(lines);
    }
}
