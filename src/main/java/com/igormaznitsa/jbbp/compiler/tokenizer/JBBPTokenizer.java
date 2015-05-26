/* 
 * Copyright 2014 Igor Maznitsa (http://www.igormaznitsa.com).
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
package com.igormaznitsa.jbbp.compiler.tokenizer;

import com.igormaznitsa.jbbp.exceptions.JBBPTokenizerException;
import com.igormaznitsa.jbbp.JBBPCustomFieldTypeProcessor;
import com.igormaznitsa.jbbp.io.JBBPByteOrder;
import com.igormaznitsa.jbbp.utils.JBBPUtils;
import java.util.*;
import java.util.regex.*;

/**
 * The Class implements a token parser which parses a String to binary block
 * parser tokens and check their format.
 * @since 1.0
 */
public final class JBBPTokenizer implements Iterable<JBBPToken>, Iterator<JBBPToken> {

  /**
   * The Next available token.
   */
  private JBBPToken nextItem;

  /**
   * The Field contains deferred error.
   */
  private JBBPTokenizerException detectedException;

  /**
   * The Pattern to break a string to tokens.
   */
  private static final Pattern PATTERN = Pattern.compile("\\s*\\/\\/.*$|\\s*(\\})|\\s*([^\\s\\;\\[\\]\\}\\{]+)?\\s*(?:\\[\\s*([^\\[\\]\\{\\}\\;]+)\\s*\\])?\\s*([^\\d\\s\\;\\[\\]\\}\\{\\/][^\\s\\;\\[\\]\\}\\{\\/]*)?\\s*([\\{\\;])", Pattern.MULTILINE);

  /**
   * The Pattern to break field type to parameters.
   */
  private static final Pattern FIELD_TYPE_BREAK_PATTERN = Pattern.compile("^([<>])?([\\w][\\w\\$]*)(?::((?:[-]?\\d+)|(?:\\(.+\\))))?$");

  /**
   * Inside table to keep disabled names for fields.
   */
  private static final Set<String> globalReservedTypeNames;

  static {
    globalReservedTypeNames = new HashSet<String>();
    globalReservedTypeNames.add("bit");
    globalReservedTypeNames.add("bool");
    globalReservedTypeNames.add("byte");
    globalReservedTypeNames.add("ubyte");
    globalReservedTypeNames.add("short");
    globalReservedTypeNames.add("ushort");
    globalReservedTypeNames.add("int");
    globalReservedTypeNames.add("long");
    globalReservedTypeNames.add("align");
    globalReservedTypeNames.add("skip");
    globalReservedTypeNames.add("$");
  }

  private final Matcher matcher;
  private int lastCharSubstingFound = -1;
  private final String processingString;

  private final Set<String> reservedTypeNames;
  
  /**
   * Constructor.
   *
   * @param str a string to be parsed, must not be null.
   */
  public JBBPTokenizer(final String str) {
    this(str,null);
  }

  /**
   * Constructor.
   *
   * @param str a string to be parsed, must not be null.
   * @param customFieldTypeProcessor custom field type processor, it can be null
   */
  public JBBPTokenizer(final String str, final JBBPCustomFieldTypeProcessor customFieldTypeProcessor) {
    JBBPUtils.assertNotNull(str, "String must not be null");
    
    if (customFieldTypeProcessor == null){
      this.reservedTypeNames = globalReservedTypeNames;
    }else{
      this.reservedTypeNames = new HashSet<String>(globalReservedTypeNames);
      for(final String customType : customFieldTypeProcessor.getCustomFieldTypes()){
        JBBPUtils.assertNotNull(customType, "Type must not be null");
        this.reservedTypeNames.add(customType);
      }
    }
    
    this.processingString = str;
    this.matcher = PATTERN.matcher(this.processingString);
    readNextItem();
  }

  /**
   * Inside method to read the next token from the string and place it into
   * inside storage.
   */
  private void readNextItem() {
     if (matcher.find()) {
      final String groupWholeFound = this.matcher.group(0);
      final String groupWholeFoundTrimmed = groupWholeFound.trim();

      final String groupCloseStruct = this.matcher.group(1);
      final String groupTypeOrName = this.matcher.group(2);
      final String groupArrayLength = this.matcher.group(3);
      final String groupName = this.matcher.group(4);
      final String groupEnder = this.matcher.group(5);

      final String skipString = this.processingString.substring(Math.max(this.lastCharSubstingFound, 0), matcher.start()).trim();
      if (skipString.length() != 0 && !skipString.startsWith("//")) {
        this.detectedException = new JBBPTokenizerException(skipString, Math.max(this.lastCharSubstingFound, 0));
      }
      else {
        JBBPTokenType type = JBBPTokenType.ATOM;

        if (groupWholeFoundTrimmed.startsWith("//")) {
          type = JBBPTokenType.COMMENT;
        }
        else if ("{".equals(groupEnder)) {
          // {
          type = JBBPTokenType.STRUCT_START;
          if (groupName != null) {
            final int position = matcher.start() + groupWholeFound.length() - groupWholeFoundTrimmed.length();
            this.detectedException = new JBBPTokenizerException("Wrong structure format, it must have only name (and may be array definition)", position);
            return;
          }
        }
        else if (groupCloseStruct != null) {
          type = JBBPTokenType.STRUCT_END;
        }
        else if (groupTypeOrName == null) {
          final int position = matcher.start() + groupWholeFound.length() - groupWholeFoundTrimmed.length();
          this.detectedException = new JBBPTokenizerException("Detected atomic field definition without type", position);
          return;
        }

        String fieldType = groupTypeOrName;
        final String arrayLength = groupArrayLength;

        int position = matcher.start();

        final String fieldName;
        if (type == JBBPTokenType.COMMENT) {
          fieldName = matcher.group(0).trim().substring(2).trim();
          position += groupWholeFound.indexOf('/');
        }
        else {
          if (type == JBBPTokenType.STRUCT_START) {
            fieldName = fieldType;
            fieldType = null;
          }
          else {
            fieldName = groupName;
          }

          position += groupWholeFound.length() - groupWholeFound.trim().length();

          this.detectedException = checkFieldName(fieldName, position);

          if (this.detectedException != null) {
            return;
          }
        }

        JBBPFieldTypeParameterContainer parsedType = null;
        if (fieldType != null) {
          final Matcher typeMatcher = FIELD_TYPE_BREAK_PATTERN.matcher(fieldType);
          boolean wrongFormat = true;

          if (typeMatcher.find()) {
            final String groupTypeByteOrder = typeMatcher.group(1);
            final String groupTypeName = typeMatcher.group(2);
            final String groupTypeExtraField = typeMatcher.group(3);

            wrongFormat = false;

            JBBPByteOrder byteOrder = null;
            if (groupTypeByteOrder != null) {
              if (">".equals(groupTypeByteOrder)) {
                byteOrder = JBBPByteOrder.BIG_ENDIAN;
              }
              else if ("<".equals(groupTypeByteOrder)) {
                byteOrder = JBBPByteOrder.LITTLE_ENDIAN;
              }
              else {
                throw new Error("Illegal byte order char, unexpected error, contact developer please [" + fieldType + ']');
              }
            }else{
              byteOrder = JBBPByteOrder.BIG_ENDIAN;
            }

            if (!wrongFormat) {
              parsedType = new JBBPFieldTypeParameterContainer(byteOrder, groupTypeName, groupTypeExtraField);
            }
          }

          if (wrongFormat) {
            this.detectedException = new JBBPTokenizerException("Wrong format of type definition [" + fieldType + ']', position);
            return;
          }
        }
        else {
          parsedType = null;
        }

        this.nextItem = new JBBPToken(type, position, parsedType, arrayLength, fieldName);
        lastCharSubstingFound = matcher.end();
      }
    }
    else {
      if (this.lastCharSubstingFound < 0) {
        this.detectedException = new JBBPTokenizerException("Wrong format of whole string", 0);
      }
      else {
        final String restOfString = this.processingString.substring(this.lastCharSubstingFound);
        if (restOfString.trim().length() != 0) {
          throw new JBBPTokenizerException("Can't recognize a part of script [" + restOfString + ']', this.lastCharSubstingFound);
        }
      }
      this.nextItem = null;
    }
  }

  
  
  /**
   * Case sensitive check that the name is among global reserved ones.
   * @param name the name to check, must not be null
   * @return true if the name is global reserved one, false otherwise.
   */
  public static boolean isGlobalReservedName(final String name) {
    return globalReservedTypeNames.contains(name);
  }
  
  /**
   * Check a field name
   *
   * @param name the name to be checked.
   * @param position the token position in the string.
   * @return JBBPTokenizerException if the field name has wrong chars or
   * presented in disabled name set, null otherwise
   */
  private JBBPTokenizerException checkFieldName(final String name, final int position) {
    if (name != null) {
      final String normalized = JBBPUtils.normalizeFieldNameOrPath(name);
      if (normalized.indexOf('.') >= 0) {
        return new JBBPTokenizerException("Field name must not contain '.' char", position);
      }
      if (this.reservedTypeNames.contains(normalized) || normalized.startsWith("$")) {
        return new JBBPTokenizerException("'" + name + "' can't be used as field name", position);
      }
    }
    return null;
  }

  public Iterator<JBBPToken> iterator() {
    return this;
  }

  public boolean hasNext() {
    return !(this.detectedException == null && this.nextItem == null);
  }

  public JBBPToken next() {
    if (this.detectedException != null) {
      final JBBPTokenizerException ex = this.detectedException;
      this.detectedException = null;
      throw ex;
    }
    final JBBPToken current = this.nextItem;
    if (current == null) {
      throw new NoSuchElementException("Parsing has been completed");
    }

    readNextItem();
    return current;
  }

  /**
   * The Operation is unsupported one.
   */
  @Override
  public void remove() {
    throw new UnsupportedOperationException("Unsupported operation");
  }
}
