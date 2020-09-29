//
// Copyright 2020  SenX S.A.S.
//
// Use of this software is governed by the Business Source License
// included in the file licenses/BSL.txt.
//
// As of the Change Date specified in that file, in accordance with
// the Business Source License, use of this software will be governed
// by the Apache License, Version 2.0, included in the file
// licenses/APL.txt.
//

grammar FLoWS;

STRING
  : '\'' ~( '\'' )* '\''
  | '"' ~( '"' )* '"'
  ;

BOOLEAN
  : 'true'
  | 'false'
  ;

LONG
  : '-'? [0-9]+
  | '0x' [0-9A-Fa-f]+
  ;
  
DOUBLE
  : '-'? [0-9]+ '.' [0-9]+
  | '-'? [0-9]+ '.' [0-9]+ ('E'|'e') '-'? [0-9]+
  | 'NaN'
  | '+Infinity'
  | '-Infinity'
  ;

// Comments and whitespaces
//WS:                 [ \t\r\n\u000C]+ -> channel(HIDDEN);
WS
  : [ \t\r\n\u000C]+ -> skip
  ;
COMMENT:            '/*' .*? '*/'    -> channel(HIDDEN);
LINE_COMMENT:       '//' ~[\r\n]*    -> channel(HIDDEN);

RETURN
  : 'return'
  ;
  
IdentifierChars
  // DO NOT INCLUDE '-' as this would prevent matching identifiers like 'LIST->'
  : [a-zA-Z_./][a-zA-Z0-9_/.]*
  ;

ARROW
  : '->'
  ;
  
DOUBLEARROW
  : '=>'
  ;
  
identifier
  : IdentifierChars
  //
  // We include special rules for typical XXX-> and ->XXX functions
  //
  | IdentifierChars ARROW
  | ARROW IdentifierChars

  // We need to explicitly list the known operators, this means operators added in extension WILL NOT
  // be usable directly, need to do EVAL(a,b,'op') instead of op(a,b)
  | '+'
  | '+!'
  | '-'
  | '**'
  | '*'
  | '/'
  | '%'
  | '>'
  | '<'
  | '<='
  | '>='
  | '=='
  | '!='
  | '&&'
  | '||'
  | '&'
  | '|'
  | '^'
  | '>>>'
  | '>>'
  | '<<'
  | '~='
  ;  

bangIdentifier
  : identifier '!'
  ;
  
identifiers
  : '(' identifier ( ',' identifier )* ')'
  ;

assignment
  : identifiers '=' ( funcCall | macroCall )
  | identifier '=' expression
  | identifier '[' compositeElementIndex ']' ( '[' compositeElementIndex ']' )* '=' expression
  ;

funcCall
  : identifier '(' argumentList? ')'
  ;

mapEntry
  : expression ':' expression
  ;
  
mapArgumentList
  : mapEntry ( ',' mapEntry )*
  ;
  
argumentList
  : expression ( ',' expression )*
  ;

// Special rule for enforcing a single return value
singleValue
  : funcCall
  | macroCall
  ;
  
expression
  : STRING
  | BOOLEAN
  | DOUBLE
  | LONG
  | singleValue
  | bangIdentifier
  | identifier
  | anonMacro
  | list
  | map
  | compositeElement
  | expression ('+'|'+!'|'-'|'**'|'*'|'/'|'%'|'>'|'<'|'<='|'>='|'=='|'!='|'&&'|'||'|'&'|'|'|'^'|'>>>'|'>>'|'<<'|'~=') expression
  | '(' expression ')'
  ;
  
compositeElementIndex
  : ( STRING | LONG | identifier | funcCall | macroCall | compositeElement )
  ;
  
compositeElement
  : ( identifier | funcCall | macroCall ) '[' compositeElementIndex ']'
  | compositeElement '[' compositeElementIndex ']'
  ;

map
  : '{' mapArgumentList? '}'
  ;
  
list
  : '[' argumentList? ']'
  ;
  
macroCall
  : '@' identifier '(' argumentList? ')'
  ;

anonMacro
  : macroParameters (ARROW|DOUBLEARROW) LONG? macroBody
  ;

parameterList
  : identifier ( ',' identifier )*
  ;

macroParameters
  : '(' parameterList? ')'
  ;

macroBody
  : '{' blockStatements? '}'
  ;

returnValue
  : funcCall
  | macroCall
  | expression
  | returnValue ',' returnValue
  ;
  
blockStatements
  : blockStatement*
  ;

blockStatement
  : funcCall ';'?
  | macroCall ';'?
  | assignment ';'?
  | RETURN returnValue ';'?
  ;

