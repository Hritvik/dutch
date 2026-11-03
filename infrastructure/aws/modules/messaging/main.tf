terraform {
  required_version = ">= 1.8.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

variable "environment" {
  type = string
}

variable "producer" {
  type    = string
  default = "ledger"
}

variable "consumer" {
  type    = string
  default = "notification"
}

locals {
  topic_name = "dutch-${var.environment}-${var.producer}-events.fifo"
  queue_name = "dutch-${var.environment}-${var.consumer}-${var.producer}-events.fifo"
  dlq_name   = "dutch-${var.environment}-${var.consumer}-${var.producer}-events-dlq.fifo"
}

resource "aws_sqs_queue" "dead_letter" {
  name                       = local.dlq_name
  fifo_queue                 = true
  message_retention_seconds  = 1209600
  visibility_timeout_seconds = 60
}

resource "aws_sqs_queue" "events" {
  name                       = local.queue_name
  fifo_queue                 = true
  visibility_timeout_seconds = 60
  message_retention_seconds  = 345600
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dead_letter.arn
    maxReceiveCount     = 5
  })
}

resource "aws_sns_topic" "events" {
  name                        = local.topic_name
  fifo_topic                  = true
  content_based_deduplication = false
}

resource "aws_sns_topic_subscription" "events" {
  topic_arn            = aws_sns_topic.events.arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.events.arn
  raw_message_delivery = true
}

resource "aws_sqs_queue_policy" "events" {
  queue_url = aws_sqs_queue.events.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "sns.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.events.arn
      Condition = { ArnEquals = { "aws:SourceArn" = aws_sns_topic.events.arn } }
    }]
  })
}

output "topic_arn" {
  value = aws_sns_topic.events.arn
}

output "queue_arn" {
  value = aws_sqs_queue.events.arn
}

output "dlq_arn" {
  value = aws_sqs_queue.dead_letter.arn
}
