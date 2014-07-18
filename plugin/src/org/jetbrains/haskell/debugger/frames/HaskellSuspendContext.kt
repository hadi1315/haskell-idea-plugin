package org.jetbrains.haskell.debugger.frames

import org.jetbrains.haskell.debugger.frames.ProgramThreadInfo
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XExecutionStack

public class HaskellSuspendContext(public val threadInfo: ProgramThreadInfo) : XSuspendContext() {
    private val _activeExecutionStack : XExecutionStack = HaskellExecutionStack(threadInfo)
    override fun getActiveExecutionStack(): XExecutionStack? = _activeExecutionStack

    /**
     * This method is not overrode, default implementation returns array of one element - _activeExecutionStack
     */
//    override fun getExecutionStacks(): Array<XExecutionStack>?
}