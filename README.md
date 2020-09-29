# FLoWS

FLoWS is a functional language fully compatible with WarpScript. The FLoWS extension allows to execute FLoWS code within WarpScript code. It also offers a FLoWS to WarpScript transpiler.

FLoWS aims to be easy to learn and understand.

To fully exploit the power of the Warp 10 Platform, more advanced users might want to later learn WarpScript. FLoWS and WarpScript share the same library of functions, so any time investment in one is quickly usable in the other.

# Enabling the FLoWS extension

Add the following line to your Warp 10 configuration:

```
warpscript.extension.flows = io.warp10.ext.flows.FLoWSWarpScriptExtension
```

then restart your Warp 10 instance.

# Provided functions

The FLoWS extension adds the functions `FLOWS` and `FLOWS->` to the WarpScript language. Please refer to the documentation for each of those functions for their syntax.

# FLoWS 101

## Comments

FloWS supports `C` and `C++` style comments.

```
// C++ Style comments
/* 
C-Style comments
*/
```

## Supported types

FLoWS supports `LONG`s and `DOUBLE`s.

```
42 		// LONG
0x2a            // LONG
3.14 		// DOUBLE
1.0E-12		// DOUBLE
```

`BOOLEAN`s are supported

```
true  // Not False
false // Not True
```

Percent encoded `STRING`s using UTF-8 are enclosed in single or double quotes.

```
'Hello'
"%F0%9F%96%96"	// 🖖
'Multiline
Strings'
```

Lists are comma separated expressions enclosed in square brackets.

```
[ 'Hello', 3.1415, 42 ]
[ 'Hello', 3.1415, 
  42	// Works on multiple lines too
]
```

Maps are comma separated *key*:*value* pairs enclosed in curly braces.

```
{ 'A':65, '@':64, 64:'@' }
{ '@':64, 64:'@',
  'A':65  // Works on multiple lines too
}
```

Accessing list and map elements is done using an intuitive syntax.

```
map['A']  // 65
map[64]   // '@'
list[0]   // 'Hello'
```

## Operators

Simple left to right precedence with optional parentheses grouping.

Binary operators: `+`, `+!`, `|`, `-`, `**`, `*`, `/`, `%`.

Comparison and logical operators: `>`, `<`, `<=`, `>=`, `==`, `!=`, `~=`, `&&`, `||`

Bitwise operators: `&`, `|`, `^`, `>>>`, `>>`, `<<`

```
A = 5 + 3 / 2.0
X = 8 + (F(x + 1) * 3.14) - 12
```

## Function calls

Comma separated list of expressions as function parameters. Functions can return 0 to N values.

```
F(1,2,'A',b)  // F is the function name
G()           // Parameterless function call
```
## Assignments

Assignments assign values to variables

```
A = 12
(x, y) = F(1)   // F MUST return two values
M[0][1] = 3.14  // Assign to list/map element
```

## Macros

Macros are sequences of statements.

```
M = (a,b,c) -> {  // 3 parameters
  ...
}
```

Macros can return 0 to N values just like functions, use `return` as last statement followed by a comma separated list of values to return.

```
M = (a,b,c) -> {  // 3 parameters
  return F(a,b), G(c)
}
```

Expected number of return values can be enforced.

```
M = (a,b,c) -> 2 {  // MUST return 2 values
  return F(a,b), G(c)
}
```

Macros are called like functions, with a comma separated list of expressions as parameters. Macro name is prefixed with `@` like in WarpScript.

```
@M(1,2,3)
(x,y) = @M(1,2,3)  // Assign return values
```

When using variables in macros you can either use the name of the variable, *e.g.* `A`, the variable will be replaced by its value at the time of the execution, or use the name suffixed with `!` to use the value of the variable at the time of the macro definition.

# License

The FLoWS extension is distributed under the Business Source License 1.1. Please refer to the file `licenses/BSL.txt` for more details.
