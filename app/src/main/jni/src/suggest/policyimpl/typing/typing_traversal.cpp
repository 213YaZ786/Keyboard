/*
 * Copyright (C) 2013 The Android Open Source Project
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

#include "suggest/policyimpl/typing/typing_traversal.h"

namespace latinime {
const bool TypingTraversal::CORRECT_OMISSION = true;
// Keyboard: off. Space substitution read a letter next to Space as a typed space and split the word
// ("racketball" became "racket all", HeliBoard issue #1351, open). The same switch as AOSP's
// TypingTraversal::CORRECT_NEW_WORD_SPACE_SUBSTITUTION, kept for CORRECT_NEW_WORD_SPACE_OMISSION below ("thisis" -> "this is").
// Its only reader is isSpaceSubstitutionTerminal (typing_traversal.h), called from Suggest::expandCurrentDicNodes
// in suggest.cpp: false means no substitution word is created; the weighting cases for CT_NEW_WORD_SPACE_SUBSTITUTION
// are then never reached, nothing else depends on the switch. Gesture typing has its own traversal.
// https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/jni/src/suggest/policyimpl/typing/typing_traversal.cpp#CORRECT_NEW_WORD_SPACE_SUBSTITUTION
const bool TypingTraversal::CORRECT_NEW_WORD_SPACE_SUBSTITUTION = false;
const bool TypingTraversal::CORRECT_NEW_WORD_SPACE_OMISSION = true;
const TypingTraversal TypingTraversal::sInstance;
}  // namespace latinime
