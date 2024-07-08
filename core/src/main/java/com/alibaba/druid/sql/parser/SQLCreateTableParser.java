/*
 * Copyright 1999-2017 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.sql.parser;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLPartitionBy;
import com.alibaba.druid.sql.ast.SQLPartitionOf;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.oracle.parser.OracleSelectParser;
import com.alibaba.druid.sql.template.SQLSelectQueryTemplate;
import com.alibaba.druid.util.FnvHash;

import java.util.List;

import static com.alibaba.druid.sql.parser.SQLParserFeature.Template;

public class SQLCreateTableParser extends SQLDDLParser {
    public SQLCreateTableParser(String sql) {
        super(sql);
    }

    public SQLCreateTableParser(SQLExprParser exprParser) {
        super(exprParser);
        dbType = exprParser.dbType;
    }

    public SQLCreateTableStatement parseCreateTable() {
        List<String> comments = null;
        if (lexer.isKeepComments() && lexer.hasComment()) {
            comments = lexer.readAndResetComments();
        }

        SQLCreateTableStatement stmt = parseCreateTable(true);
        if (comments != null) {
            stmt.addBeforeComment(comments);
        }

        return stmt;
    }

    public SQLCreateTableStatement parseCreateTable(boolean acceptCreate) {
        SQLCreateTableStatement createTable = newCreateStatement();
        createTable.setDbType(getDbType());

        if (acceptCreate) {
            if (lexer.hasComment() && lexer.isKeepComments()) {
                createTable.addBeforeComment(lexer.readAndResetComments());
            }

            accept(Token.CREATE);
        }

        createTableBefore(createTable);

        accept(Token.TABLE);

        if (lexer.token() == Token.IF || lexer.identifierEquals("IF")) {
            lexer.nextToken();
            accept(Token.NOT);
            accept(Token.EXISTS);

            createTable.setIfNotExiists(true);
        }

        createTable.setName(this.exprParser.name());

        if (lexer.token == Token.LPAREN) {
            lexer.nextToken();

            for (; ; ) {
                Token token = lexer.token;
                if (lexer.identifierEquals(FnvHash.Constants.SUPPLEMENTAL)
                        && DbType.oracle == dbType) {
                    SQLTableElement element = this.parseCreateTableSupplementalLogingProps();
                    element.setParent(createTable);
                    createTable.getTableElementList().add(element);
                } else if (token == Token.IDENTIFIER //
                        || token == Token.LITERAL_ALIAS) {
                    SQLColumnDefinition column = this.exprParser.parseColumn(createTable);
                    column.setParent(createTable);
                    createTable.getTableElementList().add(column);
                } else if (token == Token.PRIMARY //
                        || token == Token.UNIQUE //
                        || token == Token.CHECK //
                        || token == Token.CONSTRAINT
                        || token == Token.FOREIGN) {
                    SQLConstraint constraint = this.exprParser.parseConstaint();
                    constraint.setParent(createTable);
                    createTable.getTableElementList().add((SQLTableElement) constraint);
                } else if (token == Token.TABLESPACE) {
                    throw new ParserException("TODO " + lexer.info());
                } else {
                    SQLColumnDefinition column = this.exprParser.parseColumn();
                    createTable.getTableElementList().add(column);
                }

                if (lexer.token == Token.COMMA) {
                    lexer.nextToken();

                    if (lexer.token == Token.RPAREN) { // compatible for sql server
                        break;
                    }
                    continue;
                }

                break;
            }

            accept(Token.RPAREN);

            if (lexer.identifierEquals(FnvHash.Constants.INHERITS)) {
                lexer.nextToken();
                accept(Token.LPAREN);
                SQLName inherits = this.exprParser.name();
                createTable.setInherits(new SQLExprTableSource(inherits));
                accept(Token.RPAREN);
            }
        }

        if (lexer.token == Token.AS) {
            lexer.nextToken();

            SQLSelect select = null;
            if ((lexer.token == Token.IDENTIFIER || lexer.token == Token.VARIANT)
                    && lexer.isEnabled(Template)
                    && lexer.stringVal.startsWith("$")) {
                select = new SQLSelect(
                        new SQLSelectQueryTemplate(lexer.stringVal));
                lexer.nextToken();
            } else if (DbType.oracle == dbType) {
                select = new OracleSelectParser(this.exprParser).select();
            } else {
                select = this.createSQLSelectParser().select();
            }
            createTable.setSelect(select);
        }

        if (lexer.token == Token.WITH && DbType.postgresql == dbType) {
            lexer.nextToken();
            accept(Token.LPAREN);
            parseAssignItems(createTable.getTableOptions(), createTable, false);
            accept(Token.RPAREN);
        }

        if (lexer.token == Token.TABLESPACE) {
            lexer.nextToken();
            createTable.setTablespace(
                    this.exprParser.name()
            );
        }

        if (lexer.token() == Token.PARTITION) {
            Lexer.SavePoint mark = lexer.mark();
            lexer.nextToken();
            if (Token.OF.equals(lexer.token())) {
                lexer.reset(mark);
                SQLPartitionOf partitionOf = parsePartitionOf();
                createTable.setPartitionOf(partitionOf);
            } else if (Token.BY.equals(lexer.token())) {
                lexer.reset(mark);
                SQLPartitionBy partitionClause = parsePartitionBy();
                createTable.setPartitioning(partitionClause);
            }
        }

        if (lexer.token() == Token.PARTITION) {
            Lexer.SavePoint mark = lexer.mark();
            lexer.nextToken();
            if (Token.OF.equals(lexer.token())) {
                lexer.reset(mark);
                SQLPartitionOf partitionOf = parsePartitionOf();
                createTable.setPartitionOf(partitionOf);
            } else if (Token.BY.equals(lexer.token())) {
                lexer.reset(mark);
                SQLPartitionBy partitionClause = parsePartitionBy();
                createTable.setPartitioning(partitionClause);
            }
        }

        parseCreateTableRest(createTable);

        return createTable;
    }

    protected void createTableBefore(SQLCreateTableStatement createTable) {
        if (lexer.nextIfIdentifier("GLOBAL")) {
            if (lexer.nextIfIdentifier("TEMPORARY")) {
                createTable.setType(SQLCreateTableStatement.Type.GLOBAL_TEMPORARY);
            } else {
                throw new ParserException("syntax error " + lexer.info());
            }
        } else if (lexer.nextIfIdentifier("LOCAL")) {
            if (lexer.nextIfIdentifier("TEMPORARY")) {
                createTable.setType(SQLCreateTableStatement.Type.LOCAL_TEMPORARY);
            } else {
                throw new ParserException("syntax error. " + lexer.info());
            }
        }

        if (lexer.nextIfIdentifier(FnvHash.Constants.DIMENSION)) {
            createTable.setDimension(true);
        }
    }

    protected void parseCreateTableRest(SQLCreateTableStatement stmt) {
    }

    public SQLPartitionBy parsePartitionBy() {
        return null;
    }

    public SQLPartitionOf parsePartitionOf() {
        return null;
    }

    protected SQLTableElement parseCreateTableSupplementalLogingProps() {
        throw new ParserException("TODO " + lexer.info());
    }

    protected SQLCreateTableStatement newCreateStatement() {
        return new SQLCreateTableStatement(getDbType());
    }

    protected void parseOptions(SQLCreateTableStatement stmt) {
        lexer.nextToken();
        accept(Token.LPAREN);

        for (; ; ) {
            String name = lexer.stringVal();
            lexer.nextToken();
            accept(Token.EQ);
            SQLExpr value = this.exprParser.primary();
            stmt.addOption(name, value);
            if (lexer.token() == Token.COMMA) {
                lexer.nextToken();
                if (lexer.token() == Token.RPAREN) {
                    break;
                }
                continue;
            }
            break;
        }

        accept(Token.RPAREN);
    }
}
