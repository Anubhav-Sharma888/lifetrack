package com.lifetrack.app.ui

import com.lifetrack.app.data.Habit
import java.time.LocalDate

data class HabitTemplate(val name: String, val icon: String, val category: String, val description: String, val target: Int = 1, val unit: String = "times") {
    fun habit(date: LocalDate) = Habit(name = name, icon = icon, category = category, description = description,
        startDate = date, targetCount = target, targetUnit = unit)
}

val habitTemplates = listOf(
    HabitTemplate("Read a few pages", "📖", "Learning", "A little room for a new perspective.", 10, "pages"),
    HabitTemplate("Move your body", "🚶", "Health", "A walk, a stretch, a little fresh air."),
    HabitTemplate("A moment of stillness", "🌿", "Mind", "Five quiet minutes. Your own pace."),
    HabitTemplate("Drink water", "💧", "Health", "One small kindness for your body.", 6, "glasses"),
    HabitTemplate("Write one paragraph", "✍️", "Creativity", "One thought is enough to begin.")
)
