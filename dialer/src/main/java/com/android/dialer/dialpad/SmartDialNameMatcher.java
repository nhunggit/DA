/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.dialer.dialpad;

import android.content.Context;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.android.dialer.database.DialerDatabaseHelper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;

import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * {@link #SmartDialNameMatcher} contains utility functions to remove accents from accented
 * characters and normalize a phone number. It also contains the matching logic that determines if
 * a contact's display name matches a numeric query. The boolean variable
 * {@link #ALLOW_INITIAL_MATCH} controls the behavior of the matching logic and determines
 * whether we allow matches like 57 - (J)ohn (S)mith.
 */
public class SmartDialNameMatcher {

    private final static String TAG = "SmartDialNameMatcher";

    private String mQuery;

    // Whether or not we allow matches like 57 - (J)ohn (S)mith
    private static final boolean ALLOW_INITIAL_MATCH = true;

    // The maximum length of the initial we will match - typically set to 1 to minimize false
    // positives
    private static final int INITIAL_LENGTH_LIMIT = 1;

    private final ArrayList<SmartDialMatchPosition> mMatchPositions = Lists.newArrayList();

    // Anhdts su dung BtalkLatinSmartDialMap
    public static final SmartDialMap LATIN_SMART_DIAL_MAP = new LatinSmartDialMap();

    private final SmartDialMap mMap;

    private String mNameMatchMask = "";
    private String mPhoneNumberMatchMask = "";

    private Context mContext;
    private String mSchar = "+*#-.(,)/ ";
    private Object mMultiMatchObject;
    private Method mMultiMatchMethod;

    @VisibleForTesting
    public SmartDialNameMatcher(String query, Context context) {
        this(query, LATIN_SMART_DIAL_MAP, context);
    }

    public SmartDialNameMatcher(String query, SmartDialMap map, Context context) {
        mQuery = query;
        mMap = map;
        mContext = context;
        mMultiMatchObject = DialerDatabaseHelper.getInstance(mContext)
                .getMultiMatchObject();
        mMultiMatchMethod = DialerDatabaseHelper.getInstance(mContext)
                .getMultiMatchMethod();
    }

    /**
     * Constructs empty highlight mask. Bit 0 at a position means there is no match, Bit 1 means
     * there is a match and should be highlighted in the TextView.
     * @param builder StringBuilder object
     * @param length Length of the desired mask.
     */
    private void constructEmptyMask(StringBuilder builder, int length) {
        for (int i = 0; i < length; ++i) {
            builder.append("0");
        }
    }

    /**
     * Replaces the 0-bit at a position with 1-bit, indicating that there is a match.
     * @param builder StringBuilder object.
     * @param matchPos Match Positions to mask as 1.
     */
    private void replaceBitInMask(StringBuilder builder, SmartDialMatchPosition matchPos) {
        for (int i = matchPos.start; i < matchPos.end; ++i) {
            builder.replace(i, i + 1, "1");
        }
    }

    /**
     * Strips a phone number of unnecessary characters (spaces, dashes, etc.)
     *
     * @param number Phone number we want to normalize
     * @return Phone number consisting of digits from 0-9
     */
    public static String normalizeNumber(String number, SmartDialMap map) {
        return normalizeNumber(number, 0, map);
    }

    /**
     * Strips a phone number of unnecessary characters (spaces, dashes, etc.)
     *
     * @param number Phone number we want to normalize
     * @param offset Offset to start from
     * @return Phone number consisting of digits from 0-9
     */
    public static String normalizeNumber(String number, int offset, SmartDialMap map) {
        final StringBuilder s = new StringBuilder();
        for (int i = offset; i < number.length(); i++) {
            char ch = number.charAt(i);
            if (map.isValidDialpadNumericChar(ch)) {
                s.append(ch);
            }
        }
        return s.toString();
    }

    /**
     * Matches a phone number against a query. Let the test application overwrite the NANP setting.
     *
     * @param phoneNumber - Raw phone number
     * @param query - Normalized query (only contains numbers from 0-9)
     * @param useNanp - Overwriting nanp setting boolean, used for testing.
     * @return {@literal null} if the number and the query don't match, a valid
     *         SmartDialMatchPosition with the matching positions otherwise
     */
    @VisibleForTesting
    @Nullable
    public SmartDialMatchPosition matchesNumber(String phoneNumber, String query, boolean useNanp) {
        if (TextUtils.isEmpty(phoneNumber)) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        constructEmptyMask(builder, phoneNumber.length());
        mPhoneNumberMatchMask = builder.toString();

        // Try matching the number as is
        SmartDialMatchPosition matchPos = matchesNumberWithOffset(phoneNumber, query, 0);
        if (matchPos != null) {
            replaceBitInMask(builder, matchPos);
            mPhoneNumberMatchMask = builder.toString();
        }
        return matchPos;
    }

    /**
     * Matches a phone number against the saved query, taking care of formatting characters and also
     * taking into account country code prefixes and special NANP number treatment.
     *
     * @param phoneNumber - Raw phone number
     * @return {@literal null} if the number and the query don't match, a valid
     *         SmartDialMatchPosition with the matching positions otherwise
     */
    public SmartDialMatchPosition matchesNumber(String phoneNumber) {
        return matchesNumber(phoneNumber, mQuery, true);
    }

    /**
     * Matches a phone number against a query, taking care of formatting characters and also
     * taking into account country code prefixes and special NANP number treatment.
     *
     * @param phoneNumber - Raw phone number
     * @param query - Normalized query (only contains numbers from 0-9)
     * @return {@literal null} if the number and the query don't match, a valid
     *         SmartDialMatchPosition with the matching positions otherwise
     */
    public SmartDialMatchPosition matchesNumber(String phoneNumber, String query) {
        return matchesNumber(phoneNumber, query, true);
    }

    /**
     * Matches a phone number against a query, taking care of formatting characters
     *
     * @param phoneNumber - Raw phone number
     * @param query - Normalized query (only contains numbers from 0-9)
     * @param offset - The position in the number to start the match against (used to ignore
     * leading prefixes/country codes)
     * @return {@literal null} if the number and the query don't match, a valid
     *         SmartDialMatchPosition with the matching positions otherwise
     */
    private SmartDialMatchPosition matchesNumberWithOffset(String phoneNumber, String query,
            int offset) {
        if (TextUtils.isEmpty(phoneNumber) || TextUtils.isEmpty(query)
                || query.length() > phoneNumber.length()) {
            return null;
        }

        String phoneNum = phoneNumber.replaceAll("[\\+\\*\\#\\-\\.\\(\\,\\)\\/ ]", "");
        if (!TextUtils.isEmpty(phoneNum) && phoneNum.contains(query)) {
            // firstly, find the start position in original phone number.
            int start = phoneNum.indexOf(query);
            int length = phoneNumber.length();
            for (int i = start; i < length; i++) {
                char ch = phoneNumber.charAt(i);
                if (ch != phoneNum.charAt(start)) {
                    continue;
                }
                if (phoneNumber.substring(i).replaceAll("[\\+\\*\\#\\-\\.\\(\\,\\)\\/ ]", "")
                        .indexOf(query) == 0) {
                    start = i;
                    break;
                }
            }
            // secondly, find the end position in original phone number.
            int specialCount = 0;
            int queryLength = query.length();
            int end = start + queryLength;
            for (int i = start; i < length; i++) {
                char ch = phoneNumber.charAt(i);
                if (mSchar.indexOf(ch) != -1) {
                    specialCount++;
                    continue;
                }

                if (i - start + 1 - specialCount == queryLength) {
                    end = i + 1;
                    break;
                }
            }
            return new SmartDialMatchPosition(start, end);
        } else {
            return null;
        }
    }

    /**
     * This function iterates through each token in the display name, trying to match the query
     * to the numeric equivalent of the token.
     *
     * A token is defined as a range in the display name delimited by characters that have no
     * latin alphabet equivalents (e.g. spaces - ' ', periods - ',', underscores - '_' or chinese
     * characters - '???'). Transliteration from non-latin characters to latin character will be
     * done on a best effort basis - e.g. '??' - 'u'.
     *
     * For example,
     * the display name "Phillips Thomas Jr" contains three tokens: "phillips", "thomas", and "jr".
     *
     * A match must begin at the start of a token.
     * For example, typing 846(Tho) would match "Phillips Thomas", but 466(hom) would not.
     *
     * Also, a match can extend across tokens.
     * For example, typing 37337(FredS) would match (Fred S)mith.
     *
     * @param displayName The normalized(no accented characters) display name we intend to match
     * against.
     * @param query The string of digits that we want to match the display name to.
     * @param matchList An array list of {@link SmartDialMatchPosition}s that we add matched
     * positions to.
     * @return Returns true if a combination of the tokens in displayName match the query
     * string contained in query. If the function returns true, matchList will contain an
     * ArrayList of match positions (multiple matches correspond to initial matches).
     */
    @VisibleForTesting
    boolean matchesCombination(String displayName, String query,
            ArrayList<SmartDialMatchPosition> matchList) {
        StringBuilder builder = new StringBuilder();
        constructEmptyMask(builder, displayName.length());
        mNameMatchMask = builder.toString();
        final int nameLength = displayName.length();
        final int queryLength = query.length();

        if (nameLength < queryLength) {
            return false;
        }

        if (queryLength == 0) {
            return false;
        }

        // The current character index in displayName
        // E.g. 3 corresponds to 'd' in "Fred Smith"
        int nameStart = 0;

        // The current character in the query we are trying to match the displayName against
        int queryStart = 0;

        // The start position of the current token we are inspecting
        int tokenStart = 0;

        // The number of non-alphabetic characters we've encountered so far in the current match.
        // E.g. if we've currently matched 3733764849 to (Fred Smith W)illiam, then the
        // seperatorCount should be 2. This allows us to correctly calculate offsets for the match
        // positions
        int seperatorCount = 0;

        // Bkav TienNAb - Fix bug BOS-2538 - Start
        // them bien de check so ky tu in hoa trong chuoi
        int d = 0;

        ArrayList<SmartDialMatchPosition> partial = new ArrayList<SmartDialMatchPosition>();
        // Keep going until we reach the end of displayName
        while (nameStart < nameLength && queryStart < queryLength) {
            char ch = displayName.charAt(nameStart);
            // Strip diacritics from accented characters if any
            ch = mMap.normalizeCharacter(ch);
            if (mMap.isValidDialpadCharacter(ch)) {
                if (mMap.isValidDialpadAlphabeticChar(ch)) {
                    ch = mMap.getDialpadNumericCharacter(ch);
                }
                if (ch != query.charAt(queryStart)) {
                    // Failed to match the current character in the query.

                    // Case 1: Failed to match the first character in the query. Skip to the next
                    // token since there is no chance of this token matching the query.

                    // Case 2: Previous characters in the query matched, but the current character
                    // failed to match. This happened in the middle of a token. Skip to the next
                    // token since there is no chance of this token matching the query.

                    // Case 3: Previous characters in the query matched, but the current character
                    // failed to match. This happened right at the start of the current token. In
                    // this case, we should restart the query and try again with the current token.
                    // Otherwise, we would fail to match a query like "964"(yog) against a name
                    // Yo-Yoghurt because the query match would fail on the 3rd character, and
                    // then skip to the end of the "Yoghurt" token.

                    if (queryStart == 0 || mMap.isValidDialpadCharacter(mMap.normalizeCharacter(
                            displayName.charAt(nameStart - 1)))) {
                        // skip to the next token, in the case of 1 or 2.
                        while (nameStart < nameLength &&
                                mMap.isValidDialpadCharacter(mMap.normalizeCharacter(
                                        displayName.charAt(nameStart)))) {
                            // them dieu kien gap ky in hoa thu 2 thi ngat chuoi
                            if (SmartDialPrefix.isUperCaseCharacter(displayName.charAt(nameStart))){
                                d++;
                            }
                            if (d < 2) {
                                nameStart++;
                            } else {
                                break;
                            }
                        }
                        if (d < 2)
                            nameStart++;
                    }

                    // Restart the query and set the correct token position
                    queryStart = 0;
                    seperatorCount = 0;
                    tokenStart = nameStart;
                    // dat lai gia tri cho bien dem so ky tu in hoa
                    d = 0;
                } else {
                    if (queryStart == queryLength - 1) {

                        // As much as possible, we prioritize a full token match over a sub token
                        // one so if we find a full token match, we can return right away
                        matchList.add(new SmartDialMatchPosition(
                                tokenStart, queryLength + tokenStart + seperatorCount));
                        for (SmartDialMatchPosition match : matchList) {
                            replaceBitInMask(builder, match);
                        }
                        mNameMatchMask = builder.toString();
                        return true;
                    } else if (ALLOW_INITIAL_MATCH && queryStart < INITIAL_LENGTH_LIMIT) {
                        // we matched the first character.
                        // branch off and see if we can find another match with the remaining
                        // characters in the query string and the remaining tokens
                        // find the next separator in the query string
                        int j;
                        for (j = nameStart; j < nameLength; j++) {
                            // them dieu kien gap ky tu in hoa thu 2 thi cat chuoi
                            if (SmartDialPrefix.isUperCaseCharacter(displayName.charAt(j))){
                                d ++;
                            }
                            if (!mMap.isValidDialpadCharacter(mMap.normalizeCharacter(
                                    displayName.charAt(j))) || d == 2) {
                                break;
                            }
                        }
                        // this means there is at least one character left after the separator
                        if (j < nameLength - 1) {
                            // set lai gia tri chuoi moi sau khi cat trong truong hop gap ky tu in hoa thu 2
                            String remainder;
                            if (d < 2){
                                remainder = displayName.substring(j + 1);
                            } else {
                                remainder = displayName.substring(j);
                            }
                            final ArrayList<SmartDialMatchPosition> partialTemp =
                                    Lists.newArrayList();
                            if (matchesCombination(
                                    remainder, query.substring(queryStart + 1), partialTemp)) {

                                // set lai vi tri tu khoa tim kiem trung voi query trong truong hop gap ky tu in hoa thu 2
                                if (d < 2){
                                    // store the list of possible match positions
                                    SmartDialMatchPosition.advanceMatchPositions(partialTemp, j + 1);
                                    partialTemp.add(0,
                                            new SmartDialMatchPosition(nameStart, nameStart + 1));
                                } else {
                                    // store the list of possible match positions
                                    SmartDialMatchPosition.advanceMatchPositions(partialTemp, j);
                                    partialTemp.add(0,
                                            new SmartDialMatchPosition(j, j + 1));
                                }
                                // we found a partial token match, store the data in a
                                // temp buffer and return it if we end up not finding a full
                                // token match
                                partial = partialTemp;
                            }
                        }
                    }
                    nameStart++;
                    queryStart++;
                    // dat lai gia tri cho bien dem so ky tu in hoa
                    d = 0;
                    // we matched the current character in the name against one in the query,
                    // continue and see if the rest of the characters match
                }
                // Bkav TienNAb - Fix bug BOS-2538 - End
            } else {
                // found a separator, we skip this character and continue to the next one
                nameStart++;
                if (queryStart == 0) {
                    // This means we found a separator before the start of a token,
                    // so we should increment the token's start position to reflect its true
                    // start position
                    tokenStart = nameStart;
                } else {
                    // Otherwise this separator was found in the middle of a token being matched,
                    // so increase the separator count
                    seperatorCount++;
                }
            }
        }
        // if we have no complete match at this point, then we attempt to fall back to the partial
        // token match(if any). If we don't allow initial matching (ALLOW_INITIAL_MATCH = false)
        // then partial will always be empty.
        if (!partial.isEmpty()) {
            matchList.addAll(partial);
            for (SmartDialMatchPosition match : matchList) {
                replaceBitInMask(builder, match);
            }
            mNameMatchMask = builder.toString();
            return true;
        }
        return false;
    }

    public boolean matches(String displayName) {
        mMatchPositions.clear();
        if (mMultiMatchObject != null && mMultiMatchMethod != null) {
            return matchesMultiLanguage(displayName, mQuery, mMatchPositions);
        } else {
            return matchesCombination(displayName, mQuery, mMatchPositions);
        }
    }

    public ArrayList<SmartDialMatchPosition> getMatchPositions() {
        // Return a clone of mMatchPositions so that the caller can use it without
        // worrying about it changing
        return new ArrayList<SmartDialMatchPosition>(mMatchPositions);
    }

    public void setQuery(String query) {
        mQuery = query;
    }

    public String getNameMatchPositionsInString() {
        return mNameMatchMask;
    }

    public String getNumberMatchPositionsInString() {
        return mPhoneNumberMatchMask;
    }

    public String getQuery() {
        return mQuery;
    }

    boolean matchesMultiLanguage(String displayName, String query,
            ArrayList<SmartDialMatchPosition> matchList) {
        StringBuilder builder = new StringBuilder();
        constructEmptyMask(builder, displayName.length());
        mNameMatchMask = builder.toString();
        final int nameLength = displayName.length();
        final int queryLength = query.length();

        if (queryLength == 0) {
            return false;
        }
        // contains the start, not the end poing
        int[] indexs = null;
        try {
            indexs = (int[]) mMultiMatchMethod.invoke(mMultiMatchObject,
                    query, displayName, 0);
            // mMultimatch.getMatchStringIndex(query, displayName, 0);
            if (indexs == null) {
                return false;
            }
        } catch (Exception e) {
            Log.d(TAG, "Exception:" + e);
            return false;
        }

        for (int i = 0; i < indexs.length; i = i + 2) {
            int start = indexs[i];
            int end = indexs[i + 1];
            if (start >= 0 && end >= 0) {
                matchList.add(new SmartDialMatchPosition(start, end + 1));
            } else {
                Log.d(TAG, "Invalid index, start is:" + start + " end is:"
                        + end + " for name:" + displayName);
            }
        }

        for (SmartDialMatchPosition match : matchList) {
            replaceBitInMask(builder, match);
        }
        mNameMatchMask = builder.toString();
        return true;
    }
}
